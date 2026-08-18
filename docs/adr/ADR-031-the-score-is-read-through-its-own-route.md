# ADR-031: The Score Is Read Through Its Own Route, And A Contradicted Game Is A Server Fault

**Status:** Accepted
**Date:** 2026-08-15
**Deciders:** @tech-lead, @architecture-guardian

## Context

[ADR-030](ADR-030-scoring-is-derived-not-accumulated.md) put the scoring rule in the domain and
deliberately stopped there: `ScoreSheet.of(List<Player>, List<Trick>)` could derive a score, but
nothing could reach it. No route, no use case, no port method, no wiring. This decision covers the
second of EOP-15's three slices — making that score readable — and it inherits nine conditions the
five Definition-of-Done gates attached to slice A's approval.

Four of those conditions shaped the design rather than merely being discharged by it:

- The refusals in `ScoreSheet.of`, `ScoreSheet.pointsOf` and `ScoredPlay.of` were
  `IllegalArgumentException`, and `GlobalExceptionHandler.handleIllegalArgument` answers 400 while
  echoing the message verbatim. A route reaching `ScoreSheet` would therefore have reported a
  server-side data contradiction as a client error, with a `playerId` or `trickPlayId` in the
  problem body. Nothing could reach those guards before this slice; everything can now.
- [ADR-024](ADR-024-trick-play-persistence-boundary.md) forbids an acting player in any repository port
  signature, so a new read cannot authorise anybody and the use case is the only place membership
  can be established.
- [ADR-027](ADR-027-singleton-subresource-naming.md) forbids a collection of every hand. A score sheet is
  by construction every player's rows, so the prohibition has to be answered in writing rather than
  stepped around.
- [ADR-004](ADR-004-api-contract-first.md) requires `docs/api/openapi.yml` to be hand-authored
  before the controller exists.

## Decision

### The route is `GET /api/v1/sessions/{sessionId}/score`, and the contract came first

`docs/api/openapi.yml` gained the path and three schemas — `ScoredPlay`, `Standing`, `ScoreSheet` —
before a line of `ScoreController` was written, taking the document to thirteen paths and
twenty-four schemas. It is validated by parsing (`ruby -ryaml`; this machine's `python3` has no yaml
module) with every `$ref` in the file resolved, because two existing tests read the document as text
and would not notice it ceasing to be YAML.

The resource is singular for the same reason `/hand` is: there is one score per session. The five
routes `TrickController` carries are unchanged; this is the sixth behind the same flag.

### This is not the collection ADR-027 forbids, and here is why

ADR-027's prohibition is on exposing every player's *private* state through one route. What makes a
hand private is the association between a player and a card they have **not** played. A score names
only cards that are already face up on the table, plus the components and notes their players typed
when playing them — every one of which the table heard spoken aloud. The sheet cannot name a held
card because it is derived from `Trick` objects, and a `TrickPlay` exists only because a card was
played. `ScoreSheet.of` never touches a `Hand`, and no `Hand` is reachable from anything the route
returns.

So the shape ADR-027 forbids is not "one route returning something about every player" but "one
route returning what each player is entitled to keep to themselves". A score is the first and only
aggregate read in this application, and it is legitimate precisely because it aggregates public
facts. An integration test pins the distinction directly: the rows name exactly the cards that were
played and nothing else.

### There is deliberately no `409`, because a score is never unreportable

The trick-state read answers 409 before the deal, since there is no state of play to report. A score
has no such state: before anybody plays, the true answer is everybody on nothing, and `ScoreSheet`
already answers exactly that — slice A shipped a test called
`shouldListEverybodyOnNothingBeforeAnyPlay`. The published statuses are therefore `200`, `400`,
`403` and `404`, and the contract says why the fourth is missing, following the precedent set by the
resolve operation's paragraph explaining its absent `422`.

Two consequences fall out. `GetScoreUseCase` needs no `HandRepository`, because it never has to ask
whether the deck was dealt. And it makes no check on session status, so a score remains readable
after play ends — which is the point of a score.

### Two collaborators, because resolving the caller already yields the session

`GetScoreUseCase` holds `ResolvePlayerUseCase` and `TrickRepository` and nothing else. Resolving a
token returns a `ResolvedPlayer` carrying the whole `GameSession`, and a session carries its
players, so the Score Card's Name column arrives with the authorisation rather than from a second
read.

Membership needs no further check. `GameSession.playerByTokenHash` searches only the players of the
session the token was presented for, so a stranger who guesses a session identifier is refused with
`PlayerNotRecognisedException` and a 403 before any trick is read. The trick-state read needs an
extra `hasSeat` test only because a *hand* may not cover a seat; a score has no such gap. Unit tests
assert the refusal precedes the read by checking the repository was never asked — a use case that
read first and authorised second would have the whole history in memory at the moment it turned a
stranger away.

### One new port method, and it filters nothing

`TrickRepository.findTricks(UUID sessionId)` returns every trick of the session in sequence order.
It takes no acting player, satisfying ADR-024 by construction rather than by discipline.

It deliberately does not filter on `winner_play_id IS NOT NULL`. `TrickJpaRepository`'s existing
javadoc argues that whether a trick is finished is a question the trick answers about itself once
reconstituted, and a query filtering on that column would be a second authority on the same fact.
Filtering would also be wrong for scoring: the plays of an unresolved trick have already earned
their threat points, so dropping the trick would under-report a running score. `ScoreSheet` handles
this itself, giving no trick point to a trick with no winner.

### Four reads for the whole session, not three per trick

`assemble` costs three queries per trick — its plays, its cards and its components. A three-player
game reaches twenty-six tricks, so mapping it over the history would cost seventy-nine round trips — one for the trick rows and three for each trick
for one score read. `findTricks` instead fetches all plays with one `findByTrickIdIn`, one card
catalogue read and one components read, then groups in memory and reuses the existing rotation
comparator per trick. Four reads regardless of how far the game has gone: the trick rows, then their plays, cards and
components one batch each.

`assemble` was split into a fetching method and a pure overload to make this possible, and the
components accumulation was extracted into a helper both paths share.

### One exception type for eight refusals, and it is a 500

All eight route-reachable refusals became `ScoreNotDerivableException`, a domain type carrying a
nested `Reason` enum with one constant per guard and eight static factories. The handler logs the
reason and the throwable at ERROR and answers **500** with a body byte-identical to the
no-tampering-card fault: title `Internal server error`, detail `The request could not be completed.`

The reasoning is that RFC 9457 wants one problem type per *client-actionable* condition, and there
is exactly one such condition here: none of the eight is actionable, because each means the stored
game contradicts itself — a play attributed to nobody seated, a player seated twice, two tricks
colliding on identity or sequence. Eight exception types would be eight names for the same 500, and
the identifiers they carry belong in a log rather than in a response to whoever guessed a session
identifier. That is the rule `WinningPlayNotInTrickException` already established.

Static factories rather than a public constructor, because the nested `Reason` cannot be imported —
checkstyle rejects a same-package import — so every call site would otherwise have to spell
`ScoreNotDerivableException.Reason.X` and exceed the line limit.

Four guards were deliberately left as `IllegalArgumentException`: the seat range in `ScoredPlay`'s
canonical constructor and the three bounds in `Standing`'s. They interpolate only bounded integers,
so they leak nothing, and no route can reach them — `ScoredPlay.of` and `ScoreSheet.rank` supply
those arguments from already-validated domain objects. They are programming-error guards on a direct
constructor call, which is what `IllegalArgumentException` is for.

An unintended benefit: slice A's refusal tests now assert `reason()` instead of performing the
accept-after-correction dance that stood in for a message assertion. That is strictly stronger, and
it survives any rewording.

### A second controller, but one integration test

`ScoreController` is a separate class rather than a sixth handler on `TrickController`, because
`TrickController`'s tag and javadoc are scoped to dealing, playing and resolving, and because a
dedicated class gives the ADR-027 argument a natural home. It carries the same
`@ConditionalOnProperty` on `eop.features.trick-play`.

Its HTTP tests nevertheless live in `TrickControllerIntegrationTest`, as a `ReadingTheScore` group.
Every fixture that seats a table, deals, plays a whole trick and resolves it is private to that
class, and a separate test class would have to duplicate some two hundred lines of it. One
controller per concern and one integration test per feature flag is the split, and it means the
flag's whole surface is asserted in one place: eight beans absent and six routes 404 with the flag
off, eight beans present with it on.

That reason is a fact about `TrickControllerIntegrationTest` today, not a law, so it is worth naming
the exit condition: the moment those helpers move to a package-private fixture class, a separate
`ScoreControllerIntegrationTest` becomes the better arrangement and the next route behind this flag
should not be a third nested group by default.

### Multi-trick accumulation is pinned at use-case level, not over HTTP

An HTTP test that played and resolved two tricks was written, failed, and was withdrawn rather than
worked around. The `playWholeTrick` fixture plays the rotation from seat 0, but after a trick is
resolved the lead passes to the winner, so it can only ever drive the first trick of a hand.

The claim is made instead in `GetScoreUseCaseTest`, where the history is supplied directly: two
resolved tricks won by different seats give six rows, totals of 3/3/2, positions 1/1/3 and a tie
shown as a tie. This loses nothing, because `ScoreSheet.of` re-sorts by sequence itself, which makes
the adapter's ordering belt-and-braces rather than load-bearing for the sheet's correctness.

### ADR-006's reserved branch minimum is now restored

ADR-006 held a 70% branch minimum in reserve, to be restored once the domain had branching logic.
It plainly does. The bundle measures **90.19%** branch coverage, so the limit passes with twenty
points of headroom, and leaving a reserve uncollected while ADR-030 kept citing it was the sort of
gap that quietly becomes permanent. `pom.xml` now carries a `BRANCH` limit alongside the existing
`INSTRUCTION` one.

## Consequences

- The production class count rises from **112 to 119**. The seven new types are
  `ScoreNotDerivableException` (75 instructions), its nested `Reason` enum (51), `GetScoreUseCase`
  (30), `ScoreController` (16), `ScoredPlayDto` (60), `StandingDto` (38) and `ScoreSheetDto` (31).
  Every one is at **100% instruction coverage with nothing missed, and none of them contains a
  single branch**, so no branch figure can be claimed for them in either direction. Figures
  re-derived from `target/site/jacoco/jacoco.csv`, which holds exactly 119 rows.
- The suite goes from **962 to 989 tests**, all passing: 12 in `GetScoreUseCaseTest`, 9 in the new
  `ReadingTheScore` group, 3 in the flag-off test, 2 in the flag-on test, and 1 mapping test for the
  new handler. SpotBugs 0, Checkstyle 0, JaCoCo's checks met.
- Two `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` families surfaced on the new DTOs and were closed with a
  compact constructor calling `List.copyOf` on each list component, matching `TrickPlayDto`. The
  double copy the *domain* records use is belt-and-braces; the detector is satisfied by one.
- **`eop.features.trick-play` remains `false`.** Nothing shipped here is reachable in a deployed
  container, and flipping the flag is still its own story per
  [ADR-028](ADR-028-end-of-hand-without-release-or-score.md).
- The comment block above that flag in `application.yml` had to be corrected in this slice. It
  justified naming EOP-15 a predecessor on the grounds that "a player could play every card and the
  session would still report `IN_PROGRESS` with no score anywhere" — the second half is now false,
  because a score is readable. EOP-15 remains a predecessor for the first half only: nothing yet
  moves a session to `COMPLETED`. This is the same present-tense staleness that got slice A rejected
  twice, and it is this slice's own work that falsified it.
- `ADR-030:198`'s enumeration of interpolated identifiers named only `playerId` and `trickPlayId`;
  it also had to cover a `trickId` and the two refusals that name no identifier at all. Corrected,
  discharging the eighth carried condition.
- Still true and deliberately untouched: `TrickState.handComplete`'s "says nothing about the score",
  the resolve operation's "Scoring is not part of this operation", and the contract's note that
  `COMPLETED` is reserved for a hand whose score is final. `SessionStatus`'s class javadoc remains
  stale in the way ADR-030 recorded — its "because playing cards arrives with EOP-14" clause was
  already false before either slice — and slice C owns rewriting it.
- No changeset, no entity mapping and no schema change. The score is derived from rows EOP-14
  already writes.
- Slice C inherits an unchanged obligation: persisting final standings stays conditional, must be
  justified by a purpose derivation cannot serve, and a persisted standing must never be read back
  to answer the score.
- A client watching only `GET /api/v1/sessions/{sessionId}` still never sees a session finish. That
  gap is slice C's, and it is the one thing left before the flag can be flipped.

**Carried conditions for slice C** (re-derived from the tree; these are what the architecture gate
will check before approving slice C):

1. Keep `eop.features.trick-play: false` on merge; flipping the flag is its own story and its own
   audit.
2. Close the `COMPLETED` transition so a client watching only `GET /api/v1/sessions/{sessionId}`
   sees a session finish. This is the one thing left before the flag can be flipped.
3. Persisted standings stay conditional: justify by a purpose derivation cannot serve, and a
   persisted standing must never be read back to answer the score — not even for a `COMPLETED` game.
4. Rewrite `SessionStatus`'s class javadoc; its "because playing cards arrives with EOP-14" clause
   was already false before either slice.
5. Resolve the use-case observability gap: thirteen use cases, none logging, against
   `observability.md`'s INFO-at-boundaries requirement. ADR-026 is a flag-on predecessor; the flag
   must not go `true` while thirteen use cases log nothing.
6. Re-derive every cardinal in the same commit that prints it; grep the previous value and check
   each hit's enumeration and line citations, not just its number.

## Related

- [ADR-004](ADR-004-api-contract-first.md) — the contract was hand-authored before the controller,
  and the new path and schemas were validated by parsing rather than by reading.
- [ADR-005](ADR-005-error-handling-strategy.md) — the decision this slice's seventh section
  extends: one problem type per client-actionable condition is what makes eight refusals one
  exception and one 500, and what keeps the identifiers out of the body.
- [ADR-006](ADR-006-build-quality-gates.md) — amended here to collect the branch minimum it held in
  reserve, now that the bundle measures 90.19%.
- [ADR-013](ADR-013-feature-flags.md) — the sixth route is withheld by
  `@ConditionalOnProperty`, so with the flag off the bean does not exist rather than the handler
  refusing.
- [ADR-014](ADR-014-realtime-transport.md) — the contract tells a client to prefer the event stream
  and use this route to recover, because an event says only that the session moved.
- [ADR-015](ADR-015-player-identity.md) — the token is the entire control, and it is matched only
  against the players of the session it was presented for, which is what makes the membership check
  free.
- [ADR-020](ADR-020-session-concurrency-control.md) governs every write through the port this slice
  extended. `findTricks` is a read, so it takes no witness and no conditional update — but it was added to
  the interface whose three write methods all carry one, while `findCurrentTrick` — also a read — carries
  none. The reason the asymmetry is safe is that a read claims nothing and so has no state to protect. Slice three's `COMPLETED` transition is a write and will be
  bound by it in full.
- [ADR-023](ADR-023-deal-remainder-and-turn-order.md) supplies the arithmetic section six rests on: the whole
  74-card deck is dealt, so three players hold 25 or 24 cards each and a hand runs to 25 tricks. That is where the
  seventy-nine reads a per-trick assembler would have cost comes from, and it is also why the sheet counts the
  plays it finds rather than assuming a trick holds one card per seat.
  *(Note: the deck was subsequently trimmed to 68 cards by EOP-75 — see [ADR-041](ADR-041-printed-deck-has-no-aces.md).
  The derivation principle is unchanged; the concrete numbers differ.)*
- [ADR-024](ADR-024-trick-play-persistence-boundary.md) — `findTricks` takes no acting player, so authorising
  the requester stays the use case's obligation.
- [ADR-026](ADR-026-use-case-observability.md) — still `Proposed`, so neither the new controller nor
  the new use case logs anything; only the exception handler does, and that class already had a
  logger.
- [ADR-027](ADR-027-singleton-subresource-naming.md) — cited for its prohibition. The argument above is
  the written answer that slice A's gates required.
- [ADR-028](ADR-028-end-of-hand-without-release-or-score.md) — the flag stays `false`, and this slice discharges
  one of the two remaining predecessors it names.
- [ADR-030](ADR-030-scoring-is-derived-not-accumulated.md) — the decision this slice makes readable,
  and the source of the debts discharged here.
