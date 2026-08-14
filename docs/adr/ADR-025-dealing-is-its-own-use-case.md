# ADR-025: Dealing Is Its Own Use Case, and the Deal Follows the Start Write

**Status:** Accepted
**Date:** 2026-08-13
**Deciders:** @architecture-guardian, @security-auditor, @tech-lead

## Context

EOP-14 Slice C2 adds the three use cases that ADR-024 named while it was describing the ports
beneath them: `DealHandsUseCase`, `PlayCardUseCase` and `ResolveTrickUseCase`. The slice plan
recorded on EOP-14 described this work as "dealing wired into session start behind the flag", and
that phrase turned out to describe something the layer below will not do.

Three facts bear on the shape of the work, and the third is weaker than it first appears.

`StartSessionUseCase` already exists and its javadoc explicitly refuses to deal, on the grounds that
"dealing is EOP-14, and putting it behind the same call would make two very different failures
indistinguishable to the caller." That was written before the ports existed, as a note about scope.

`HandRepository.recordDeal` refuses a session that is not in play. Its contract throws
`SessionNotJoinableException`, and C1's handoff recorded that this **includes the case where the
session had not started at the moment of the write**. So the status transition has to be *written*
before the deal is attempted. Written, not committed — and the distinction is load-bearing, because an
earlier draft of this ADR said "committed" and built an argument on it. What `recordDeal` actually
depends on is a predicate: `GameSessionJpaRepository.claimDeal` carries `AND s.status = :required` with
`IN_PROGRESS` supplied, and `SessionRepositoryAdapter.recordStarted` sets that column through
`advanceStatus`. A transaction reads its own uncommitted writes at every isolation level, so if the two
writes were ever to share a transaction the predicate would match.

The use-case layer has no transactions. `UseCaseConfiguration` exists so that no use case carries a
Spring import, and AGENTS.md requires that layer to have none. No use case may therefore demarcate a
transaction spanning `SessionRepository.recordStarted` and `HandRepository.recordDeal` *itself*, and no
port may be invented whose only purpose is to hold one open — that would put the persistence model's
boundaries into the layer defined by being ignorant of them.

That is a constraint on where demarcation may live, and it is not impossibility. A `@Transactional`
method in the outer ring — on a controller, or on a Spring-managed composition bean — calling
`StartSessionUseCase` and then `DealHandsUseCase` joins both adapter methods into one transaction
through the default `REQUIRED` propagation they already carry, with zero framework imports added to
`usecase`. Outer-ring transaction demarcation is where Clean Architecture puts it. **Starting and
dealing can be made one atomic act.** The seam recorded here is therefore chosen, not forced, and the
question is not whether atomicity is reachable but what it costs; the cost, which is a broadcast event
that cannot be rolled back, is weighed under Alternatives considered.

## Decision

### 1. Dealing is a use case in its own right

`DealHandsUseCase` is a class, not a step. It resolves the acting player, requires the facilitator
role, requires enough seated players, reads the deck, shuffles it, deals it and records the deal. It
does not start a session and `StartSessionUseCase` does not deal.

This is what keeps the refusals separable. "You are not the facilitator", "there are only two of
you", "the lobby is already closed" and "the cards are already dealt" are four different answers
demanding four different things of the client, and a caller who receives them from one endpoint
cannot tell which of two writes refused it.

### 2. The deal follows the start write, and the window between them is accepted

A session can exist in a started-but-undealt state. That state is reachable by a client that starts a
session and then fails to call the deal, by a crash between the two writes, and by a network timeout
on the second call.

It is accepted rather than prevented because it is recoverable and because recovery is free:
`recordDeal` writes the opening leader seat only where no leader seat is recorded, so a retry either
deals or answers `HandAlreadyDealtException`, and never deals twice. Retrying the deal is safe from
any number of callers, which is the property that makes the window uninteresting.

"Accepted" is the accurate word and "unavoidable" would not be. The window is preventable by the
outer-ring composer described under Alternatives considered; it is accepted because the failure it
would remove is recoverable and the failure it would introduce — a `GAME_STARTED` event broadcast for
a session whose deal then rolled back — is not.

Recognising the state and prompting the facilitator to finish the deal is a read-model concern and
belongs to the slice that adds the route.

### 3. No use case pre-checks a state the conditional write already arbitrates

`DealHandsUseCase` does not read the session's status, and does not ask whether hands are already
dealt, before calling `recordDeal`. The conditional write is the only check that holds under
concurrency (ADR-020); a pre-check reads state, lets go of it, and then writes, which is an illusion
of safety that also doubles the number of places the rule is written down.

The defence in depth that is real here is a different pairing: the use case authorises the *identity*
of the requester, which no port can do, and the port enforces the *state*, which no use case can do
atomically.

`PlayCardUseCase` is the one apparent exception and is not one. It calls `hand.resolve(card)` and
compares the acting seat against the recorded leader seat before writing anything, which duplicates
refusals `Trick.acceptPlay` would raise anyway. Those pre-flights buy ordering, not safety: they keep a
doomed play from committing a trick row first, and they turn what would surface as a 500 into a
distinguishable 403 or 422. Decision 9 records what that ordering rests on and how it can break.

### 4. Authorisation is the first statement of all three use cases

ADR-024 recorded that the failure paths beneath these use cases are an oracle: a conditional update
matching no rows causes the adapter to re-read the session and answer one of five distinguishable
exceptions, revealing that the session exists, its status, whether hands are dealt, and which seat
leads. That is the right answer for a member and a disclosure to anyone else, and only the layer
above can tell the two apart.

So `DealHandsUseCase`, `PlayCardUseCase` and `ResolveTrickUseCase` each call `ResolvePlayerUseCase`
as their first statement, before reading a hand, a trick or a card. `PlayCardUseCase` additionally
derives the acting seat from the resolved player, and `PlayCardCommand` carries no seat, no player
identifier, no suit and no rank, so a caller-supplied seat and a forged card are not expressible
rather than merely rejected.

### 5. Any member may resolve a trick; only the facilitator may deal

Dealing closes the deck and is the facilitator's act, so `DealHandsUseCase` requires
`Player.canStartPlay()`. Resolving a complete trick is a mechanical consequence of the last card, so
`ResolveTrickUseCase` requires membership only. Gating resolution on the facilitator would stall a
table whose facilitator has dropped, in the one situation where every player can already see who won.

### 6. Shuffling is a port, and the whole-deck read is a third method on the existing card port

`Hands.deal` is deliberately pure and deliberately does not shuffle, which is what makes a deal
assertable. `DeckShuffler` is therefore a port in the use-case layer, implemented once by
`SecureRandomDeckShuffler`; the shuffle is a security control, because the deck's composition is
published reference data and a predictable order lets a player deduce other hands.

`CardRepository.findWholeDeck()` is added to the existing port rather than to a new one. ADR-024's
standing instruction that a new aggregate gets a new adapter does not bite: the deck is the same
aggregate the port already serves. It returns a list rather than a page because a paginated deal is
one forgotten loop away from dealing a truncated deck, which produces a playable-looking game with
cards missing.

### 7. The deal returns nothing

`DealHandsUseCase.execute` is `void`. A result carrying every hand is exactly the shape that leaks
private information — the reason `Hands.toString()` names no card — and the facilitator has no more
right to see the table's cards than anyone else. Each player reads their own hand through a
per-player query in a later slice.

### 8. None of the three broadcasts anything, and that is a gap whose only enforcement is this page

`DealHandsUseCase`, `PlayCardUseCase` and `ResolveTrickUseCase` take no `SessionEventPublisher`. Every
state change the system had before this slice announces itself — `SessionEventType` declares
`PLAYER_JOINED` and `GAME_STARTED`, and `StartSessionUseCase` publishes the second — so these three are
the first writes in the system that change what every player at the table can see and tell nobody.

The silence is deliberate, and the reason is that an event name is a wire contract. `SessionEventType`
holds the name that appears in the `event:` field of an SSE frame, and its own javadoc records that the
name is fixed by `docs/api/openapi.yml`. Contract-first is ADR-004, and the published contract for the
trick-play routes is Slice D's work. A constant minted here would be a wire name invented in a slice
that is not allowed to publish one, and it would have to be renamed the moment the contract was
written. So this slice adds no `SessionEventType` constant, and the three use cases have no publisher
to give one to.

The consequence is precise. Until the broadcast is wired, a client learns that a hand was dealt, that a
card was played or that a trick was resolved **only by re-reading** — through `GetSessionStateUseCase`,
or the per-player hand query a later slice adds. A whole trick can be played out with the stream
silent. Slice D owns the event names in the contract; Slice E owns passing `SessionEventPublisher` into
these three use cases and publishing on each write, which is one constructor parameter and one call per
class.

What makes this worth a numbered decision rather than a handoff note is that **nothing in the
repository fails while it is missing.** There is no test that a deal publishes, because a publisher that
is not a collaborator cannot be asserted on; the flag being false hides the gap rather than reporting
it; and a reader of the three use cases sees no absence, only a shorter constructor. A Jira handoff is
read once, by the next person to pick up the story, and then never again. This paragraph is the only
artefact that a maintainer who wonders in six months why the stream goes quiet during a trick will
find, and that is exactly the job an ADR exists to do.

### 9. The trick row is opened before the play is accepted, and that ordering is safe only by exhaustion

`PlayCardUseCase` builds the candidate `TrickPlay`, then writes the trick row when the trick is being
opened, then calls `Trick.acceptPlay`. Building the play first is what stops an over-long note or a
malformed component list from committing a trick row that never receives a card — that ordering was
wrong in the first commit of this slice and is fixed by `fcb6fd5`. But `openTrick` still commits before
`acceptPlay` runs, so the orphan-trick window is closed by an argument rather than by a mechanism.

The argument has to be an exhaustion over the refusals `Trick.acceptPlay` actually makes, which means
reading `Trick.java:360-384` rather than the exception vocabulary. Taken in the order the method
applies them, on an **opening** play — the only path that writes a trick row:

| Refusal in `acceptPlay` | Why it cannot fire after `openTrick` |
| --- | --- |
| `IllegalArgumentException`, seat outside the table (`Trick.java:366-369`) | Unreachable. `actingSeat` is read from the resolved `Player` (`PlayCardUseCase.java:140`), never from the request, and the domain bounded it when the seat was assigned. |
| `NotYourSeatException` (`Trick.java:372`) | **Inexpressible, not pre-flighted.** The check compares `candidate.seatOrder()` with `actingSeat`, and the candidate is constructed *with* `actingSeat` (`PlayCardUseCase.java:181-190`). The two operands are the same value by construction, so no input can make them differ. |
| `IllegalArgumentException` from `hands.handOf(actingSeat)` (`Trick.java:375`) | Pre-flighted by `hands.hasSeat(actingSeat)` (`PlayCardUseCase.java:147-149`), which refuses with `PlayerNotInSessionException` first. |
| `PlayerMismatchException` (`Trick.java:378`) | Unreachable, but **for a reason neither pre-flight supplies.** It compares `hand.playerId()` — frozen into `Hands` when the deal was recorded — with the resolved player's identifier. They can only disagree if a seat changes hands after the deal, and no such path exists: `seatPlayer` is guarded by `touchWhileInStatus(..., LOBBY, ...)` (`SessionRepositoryAdapter.java:96-103`), `GameSession.join` refuses unless the status accepts new players (`GameSession.java:163-164`) and only `LOBBY` does (`SessionStatus.java:38-40`), and `GameSession` has no leave, remove or vacate method at all. The seat-to-player map is immutable from the moment the session starts. |
| `OutOfTurnException` (`Trick.java:243`, via `assertSeatMayPlay` at `:231`) | Pre-flighted by the leader-seat comparison (`PlayCardUseCase.java:168-170`), which is what makes the play an opening play in the first place. |
| `IllegalStateException`, seat holding no cards (`Trick.java:240`, same helper) | Pre-flighted by `hand.resolve(card)` (`PlayCardUseCase.java:156`), which cannot succeed against an empty hand. |
| `CardNotInHandException` (raised in `Hand.java:115`, reached through `assertLegalPlay` at `Trick.java:205`) | Pre-flighted by the same `hand.resolve(card)` — the use case calls the identical method on the identical hand before the write. |
| `MustFollowSuitException` (`Trick.java:214`, same helper) | Cannot fire on an opening play: no suit has been led, so `ledSuit()` is empty and `assertLegalPlay` returns at `Trick.java:209`. |

`Trick`'s private constructor adds five more refusals — a seat playing twice, the same card twice, one
player holding two seats, duplicate play identifiers, and a winner foreign to the trick
(`Trick.java:61-81`) — all raised as `IllegalArgumentException`. None can fire here either: the trick
is empty when it is opened, so the four duplication invariants have nothing to duplicate, and
`acceptPlay` sets no winner. Note that `AlreadyPlayedInTrickException` and `CardAlreadyPlayedException`
are *declared* in `entity` and *mapped* in `GlobalExceptionHandler`, but nothing in production throws
either; the constructor's `IllegalArgumentException`s are the refusals that actually stand in their
place. An earlier version of this decision listed those two exceptions among the refusals `acceptPlay`
makes and omitted three that it does make, which made the enumeration unverifiable against the method
it claimed to exhaust. Corrected 2026-08-13.

That argument is an exhaustion over the refusals `Trick.acceptPlay` has *today*. **Any refusal added to
`acceptPlay`, or to `Trick`'s constructor, reopens the window**, silently, in a class that does not
mention trick rows. The
durable fix is to move the write after acceptance and let the repository persist the trick and its
first play together, which changes the `TrickRepository` contract and belongs with the slice that
revisits it. Until then this table is the guard: a change to `acceptPlay`'s refusals must be
checked against it, row by row.

## Consequences

- A client starts a session and then deals: two calls, and the second one is retryable. Slice D
  publishes both, and the OpenAPI contract has to describe the intermediate state honestly.
- `StartSessionUseCase` keeps one reason to change and gains no collaborators. It has four; a version
  that dealt would have nine.
- Every refusal on the deal path names one cause. That is only true because the two writes are two
  calls.
- The started-but-undealt state is real and observable, and any read model that assumes a started
  session has hands is wrong. `HandRepository.findBySessionId` answers empty there, and
  `HandNotDealtException` is the mapped 409 for anything that needs them.
- Three use cases now exist that can reach the five trick-play tables C1 shipped, so
  `eop.features.trick-play` gates the three beans in `UseCaseConfiguration`. With the flag off no
  bean exists that calls the ports which write a hand, a trick or a play — the adapter behind those
  ports is an unconditional `@Repository` and is created either way, so what the flag withholds is
  every caller of it rather than the capability itself — which is what makes C1's containment claim
  true rather than intended.
- End of hand is not recognised in this slice, and the whole of the shortfall is one line. That line is
  the `nextLeaderSeat` assignment in `ResolveTrickUseCase.execute`, which reads
  `final var nextLeaderSeat = resolved.nextLeaderSeat(seatsHoldingCards).orElse(resolved.winningSeat());`
  — `ResolveTrickUseCase.java:137` as this ADR is written, and identified by that expression rather than
  by the line number alone, because the number moves and the expression does not. It writes the winning
  seat as the next leader even when the winner holds
  no card, because `TrickRepository.recordResolution` takes an `int` and the port has no value meaning
  "nobody leads next". It is harmless while it lasts — no seat can play, so no read of the column can
  mislead a legal move — and Slice E replaces that line together with the port signature that forces
  it. A reviewer of Slice E should expect the diff to touch both. **An earlier version of this bullet
  pinned the shortfall to `ResolveTrickUseCase.java:134`, which is the `throw` inside the
  `WinningPlayNotInTrickException` guard at `:131-135` — an integrity check, not a placeholder. An
  implementer who had followed that citation literally would have deleted the guard. Corrected
  2026-08-13; the `.orElse` expression is quoted above so the pin cannot rot back into pointing at a
  guard.**
- Nothing in the system announces a deal, a play or a resolution. See decision 8: the stream is silent
  for the whole of a trick, and the only closing date is Slice E's.

## Alternatives considered

**Deal inline inside `StartSessionUseCase`, behind the flag.** Rejected. It would have made the two
writes look atomic while remaining two writes, so the started-but-undealt window would still exist
but would no longer have a caller who could close it. It also gives one class two reasons to change
and collapses four distinct refusals into one response.

**Let the start path hold an `Optional<DealHandsUseCase>` so the flag-off path is unchanged.**
Rejected. An optional collaborator whose presence depends on configuration makes the start path
behave differently in production and in the suite, and no test of the start path alone reveals which
behaviour is under test.

**One transaction spanning both writes, demarcated inside the use-case layer.** Rejected, and this is
the narrow version of the claim. It requires either a Spring transaction import in the use-case layer,
which AGENTS.md forbids and `UseCaseConfiguration` exists to avoid, or a port whose only purpose is to
hold a transaction open across two aggregates — which would put the persistence model's boundaries into
the layer that is supposed to be ignorant of them.

**One transaction spanning both writes, demarcated in the outer ring.** Rejected on cost, not on
principle — and an earlier draft of this ADR wrongly claimed this option did not exist at all. It does,
and it works. A `@Transactional` method in `adapter/web`, or on a Spring-managed composition bean,
calls `StartSessionUseCase` and then `DealHandsUseCase`. `SessionRepositoryAdapter.recordStarted` and
`TrickPlayRepositoryAdapter.recordDeal` are both `@Transactional` with the default `REQUIRED`
propagation, so both join the caller's transaction rather than opening their own; `claimDeal`'s
`status = IN_PROGRESS` predicate matches the uncommitted `advanceStatus` because a transaction reads its
own writes; the two writes commit or roll back together; and `usecase` gains no import, because
demarcating a transaction in the outer ring is the textbook Clean Architecture placement rather than a
violation of it.

It is declined for a reason that has nothing to do with imports. `StartSessionUseCase` publishes
`SessionEventType.GAME_STARTED` through `SessionEventPublisher` as part of its own execution, and that
publisher is an in-process SSE broadcast (ADR-014), not a transactional resource. Inside a composed
transaction the event reaches every subscriber before the deal has committed, so a deal that then rolls
back leaves every client at the table told the game has started while the database says it never did.
That trades a recoverable inconsistency — the started-but-undealt window of decision 2, which any retry
closes — for an unrecoverable one, since a delivered SSE frame cannot be withdrawn. Making the composer
safe means publishing after commit rather than during it: a transaction synchronisation, or an outbox.
Both are real designs and either is a larger change than the window it removes deserves. If a later
slice wants start-and-deal to be one act, publish-after-commit is its prerequisite and deserves its own
ADR; it must not arrive as a `@Transactional` annotation added to a controller.

**A single "start and deal" endpoint in front of both use cases.** Not rejected, deferred. It is a
composition decision that belongs with the route, and it stays available precisely because the deal
is its own use case.

## Relations

- **ADR-005** — RFC 9457 problem details. The two new refusals get two new handlers in
  `GlobalExceptionHandler`, both 409: `NoTrickToResolveException` ("no trick has been led in this
  session yet") and `TrickNotCompleteException`, whose detail names the seat still to play. That seat
  number is a deliberate disclosure to a member of the session and is only defensible because decision 4
  puts authorisation before every read.
- **ADR-013** — feature flags via `@ConditionalOnProperty` and `application.yml`. `eop.features.trick-play`
  is registered there, defaulted false, and gates the three new beans.
- **ADR-014** — Server-Sent Events as the real-time transport. None of these three use cases takes a
  `SessionEventPublisher`, so none of them broadcasts, and the stream says nothing for the whole of a
  trick; decision 8 records why and who closes it. The same publisher being non-transactional is what
  rules out the outer-ring composer under Alternatives considered.
- **ADR-004** — contract-first. The wire names for a deal, a play and a resolution belong in
  `docs/api/openapi.yml`, which is why this slice mints no `SessionEventType` constant.
- **ADR-018** — UUIDv7 identifiers minted above the persistence layer, which is why the deal mints a
  hand identifier per seat through `IdentifierGenerator`.
- **ADR-019** — the identity token is the whole authorisation control, so `ResolvePlayerUseCase` is
  the seam all three use cases go through.
- **ADR-020** — compare-and-set on the session row. Decision 3 here is a direct consequence.
- **ADR-023** — the whole deck is dealt with the remainder on the lowest seats, and turn order passes
  to the winner only if the winner still holds a card. Obligation 1 of that ADR — derive the acting
  player from the authenticated identity and refuse a caller-supplied seat — is discharged by
  decision 4 here.
- **ADR-024** — the ports and the adapter beneath these use cases, and the source of the requirement
  that each of the three authorises the requester first. `WinningPlayNotInTrickException` gains its
  only thrower in `ResolveTrickUseCase`.
