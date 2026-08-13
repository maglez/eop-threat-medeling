# ADR-025: Dealing Is Its Own Use Case, and the Deal Follows the Start Write

**Status:** Accepted
**Date:** 2026-08-13
**Deciders:** @architecture-guardian, @security-auditor, @tech-lead

## Context

EOP-14 Slice C2 adds the three use cases that ADR-024 named while it was describing the ports
beneath them: `DealHandsUseCase`, `PlayCardUseCase` and `ResolveTrickUseCase`. The slice plan
recorded on EOP-14 described this work as "dealing wired into session start behind the flag", and
that phrase turned out to describe something the layer below will not do.

Three facts collide.

`StartSessionUseCase` already exists and its javadoc explicitly refuses to deal, on the grounds that
"dealing is EOP-14, and putting it behind the same call would make two very different failures
indistinguishable to the caller." That was written before the ports existed, as a note about scope.

`HandRepository.recordDeal` refuses a session that is not in play. Its contract throws
`SessionNotJoinableException`, and C1's handoff recorded that this **includes the case where the
session had not started at the moment of the write**. So the status transition has to be committed
before the deal is attempted, not alongside it.

The use-case layer has no transactions. `UseCaseConfiguration` exists so that no use case carries a
Spring import, and AGENTS.md requires that layer to have none. A single transaction spanning
`SessionRepository.recordStarted` and `HandRepository.recordDeal` would need
`org.springframework.transaction` in the package that is defined by not having it, or a third port
whose only purpose is to hold a transaction open across two aggregates.

There is therefore no arrangement in which starting and dealing are one atomic act, and the question
is only where the seam goes and who is told about it.

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
  bean exists that can write a hand, a trick or a play, which is what makes C1's containment claim
  true rather than intended.

## Alternatives considered

**Deal inline inside `StartSessionUseCase`, behind the flag.** Rejected. It would have made the two
writes look atomic while remaining two writes, so the started-but-undealt window would still exist
but would no longer have a caller who could close it. It also gives one class two reasons to change
and collapses four distinct refusals into one response.

**Let the start path hold an `Optional<DealHandsUseCase>` so the flag-off path is unchanged.**
Rejected. An optional collaborator whose presence depends on configuration makes the start path
behave differently in production and in the suite, and no test of the start path alone reveals which
behaviour is under test.

**One transaction spanning both writes.** Rejected. It requires either a Spring transaction import in
the use-case layer, which AGENTS.md forbids and `UseCaseConfiguration` exists to avoid, or a port
whose only purpose is to hold a transaction open across two aggregates — which would put the
persistence model's boundaries into the layer that is supposed to be ignorant of them.

**A single "start and deal" endpoint in front of both use cases.** Not rejected, deferred. It is a
composition decision that belongs with the route, and it stays available precisely because the deal
is its own use case.

## Relations

- **ADR-005** — plain-Java domain types, which is why the two new refusals are plain exceptions.
- **ADR-013** — feature flags via `@ConditionalOnProperty` and `application.yml`. `eop.features.trick-play`
  is registered there, defaulted false, and gates the three new beans.
- **ADR-014** — the database is the only authority on session state, which is why every use case
  re-reads and none caches.
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
