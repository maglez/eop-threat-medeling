# ADR-030: The Score Is Derived From Play, Not Accumulated Alongside It

**Status:** Accepted
**Date:** 2026-08-14
**Deciders:** @tech-lead, @architecture-guardian

## Context

EOP-15 asks for three things: that the server keeps score, that players can see it, and
that the game reaches an end with a winner. This ADR records the decisions taken in the
first of the story's three slices, which ships the scoring rule as pure domain types and
nothing else — no schema, no route, no use case. The reading slice and the end-of-game
slice follow it.

The ticket's constraints are unusually specific for this backlog. Scores must be
"computed and stored server-side", and "a client must never assert its own score".
Scoring must live "in the domain layer, not the controller". The shipped rule is one
point for a threat on your own card plus one point for taking the trick, and the ticket
says in terms that it "must not be embellished". Unit tests must cover an unlinked play,
a trick won by a trump, and a tie.

Three things had already been left open for this story to settle, in the code rather than
in a document. `TrickPlay`'s Javadoc says that components "are not required even when the
threat was linked" and that "scoring (EOP-15) decides what it does with a linked threat
that names nothing". `TrickState.handComplete`'s Javadoc says it "says nothing about the
score". And `SessionStatus.COMPLETED` is documented as meaning that every trick has been
played *and the score is final*, which ADR-028 assigned to this story and no code reaches
yet.

The shape of the answer is also already fixed, by a printed artefact rather than by us.
Microsoft's Score Card has five columns — Name, Points, Card, Component(s), Notes on
Threat — and the ticket requires that a player's view of a running game be
reconstructible into that shape. Component(s) is plural in the original because one
card's threat may be connected to several parts of the system.

## Decision

### The score is derived from the tricks, never accumulated alongside them

`ScoreSheet.of(players, tricks)` is a pure function. There is no running total in the
domain, no counter column in the schema, and no increment anywhere on the play path.
Every figure the application will ever publish is recomputed from the `trick_play` rows
and each trick's winning play.

The alternative — a total updated as each trick resolves — was rejected because it
creates a second authority. Once a stored total and the plays that produced it can
disagree, there is no way to tell which of the two is the game, and the disagreement is
silent: a total that is wrong by one point looks exactly like a total that is right. A
derived score cannot drift from the play it describes, because it has no independent
existence to drift from.

This is also the more complete reading of "computed and stored server-side". What that
constraint protects is *authority*: the server decides the score and the client never
asserts it. A derivation satisfies that in full — the client is not merely disbelieved,
it has nothing to assert against — whereas a stored figure would additionally introduce a
write path that could be wrong. Persisting the final standings when a game completes
remains open to the third slice, as a projection of a completed game's outcome rather
than as the authority for it; that is additive and does not disturb this decision.

Two conditions bind that opening, because a projection is exactly how the two-authority
problem returns by the back door. Persisting standings in the third slice must be
justified by a stated purpose that derivation cannot serve — and note that
`docs/requirements/PRD-eop-card-game.md` excludes any ranking that outlives a single
session, which is the main thing such a table would otherwise buy. And a persisted
standing must never be read back to answer the score, not even for a `COMPLETED` game
where the rows happen to be sitting there and reading them would be cheaper. The moment
it is, there are two authorities for one number and the drift this decision exists to
prevent is back. If neither condition can be met, the third slice persists nothing.

### The rule stays as written, and richer scoring is recorded as an option not built

One point for a threat connected to your own card, one point for taking the trick. That
is the whole rule.

The deck's author tried a richer system in v0.21 — four points for a threat on your own
card, three, two and one for the first, second and third threat on somebody else's, plus
one for the trick, two for a face card and three for an ace — and abandoned it because
"players (even the dedicated repeat playtesters) were looking to me at the end of every
round to ascertain who scored". That is a constraint on human arithmetic at a table, and
it does not bind a server. So richer scoring becomes cheap the moment the server keeps
score, and it is worth having on record as the obvious next thing this design enables.

It is deliberately not built now. Shipping the simple rule first is what makes the
digital form's advantage measurable rather than merely asserted, and a rule nobody has
played with is a poor thing to encode into a first release. The beginner relaxation the
whitepaper mentions — counting threats on other players' cards, which the written rule
excludes — is likewise recorded as a possible future toggle rather than built alongside
the written rule. Two scoring systems behind a switch is twice the surface and no
evidence.

### A linked threat that names no component still scores

This is the decision `TrickPlay` handed to this story, and it goes in favour of scoring.

The written rule keys on the threat being connected to the system, not on a text box
being filled. The application holds no model of the system under discussion — it stores
only what a player types when a card is played — so it *cannot* validate a component
name; the best it could do is check that the string is non-empty. Making a point
conditional on unvalidated client free text would put the client back in charge of its
own score by a side door, which is the one thing the ticket forbids. And the Score Card's
Component(s) column is legitimately blank on plenty of real rows: a threat can be
described in the Notes column and connected in conversation without anybody naming a
component.

The converse is unchanged and was never in doubt: a play with `threatLinked` false scores
no threat point, because that is the written rule and because the card was played without
a threat at all.

### A row's two points stay separate rather than pre-summed

`ScoredPlay` carries `threatPoint` and `trickPoint` as distinct booleans and exposes
`points()` as their sum.

The Score Card is read by humans in the middle of a game, and a row that scored one point
because the threat landed looks nothing, to a player, like a row that scored one point
because the card took the trick. A single integer makes the two indistinguishable at
exactly the moment somebody asks why a total is what it is. Keeping them apart also makes
the ordinary-but-surprising row self-explanatory: a play with no threat linked that still
takes the trick scores one point, and that is the rule working rather than a defect.

### Ties are shown and never broken

Standings use competition ranking. Four players on 7, 5, 5 and 2 hold positions 1, 2, 2
and 4. `Standing.tied` is published although a caller could derive it from the positions,
because the interesting fact about a shared position is that it is shared, and a client
that has to compute that for itself will eventually compute it differently from us.

No tie-break exists to get wrong later, because none is invented now. A tie at the top is
reported as a shared lead, which is what the ticket asks for and also what the game is:
two people who scored the same scored the same.

A tie *within a trick* is a different matter and is impossible — the deck holds no two
cards of the same suit and rank, so `Trick`'s winner rule always has a strict answer.
That impossibility belongs to the trick, not to the score sheet.

### A display name is never a key

`DisplayName` is free text, unverified, and explicitly not unique: two people at the same
table may pick the same name and the humans on the call disambiguate them (ADR-015). So
every row and every standing keys on `playerId`, and the name travels for rendering only.

`ScoreSheet.pointsOf` takes a player identifier and refuses one that is not seated,
rather than answering zero. Zero is a legitimate score, so returning it for somebody who
is not in the game would be a plausible lie of exactly the kind `TrickState`'s design
notes warn about.

### An unresolved trick contributes threat points but no trick point

The list of tricks handed to the sheet may end with one still on the table. Its plays'
threats score; nobody is awarded the trick, because nobody has won it yet.

Two things follow. A running score is monotone — a player's total can never fall as play
proceeds, which is what a scoreboard has to promise to be worth showing. And the same
class serves both the mid-game read and the final standings, with no second code path for
"the score so far" that could disagree with "the score".

### Deciding the game has ended is not the score sheet's job

`ScoreSheet` reports the score of the tricks it was given. It does not know whether more
tricks are coming, and it has no opinion on whether the game is over.

This follows the seam `GameSession.start()` already established, where starting a session
"establishes that the lobby is closed and nothing more" and dealing is a separate use
case, precisely so that the transition is testable without the card-dealing machinery.
The transition to `COMPLETED` is the session's, and it arrives in this story's third
slice.

## Consequences

- Three new pure domain types — `ScoredPlay`, `Standing`, `ScoreSheet` — with zero
  framework imports. The production class count rises from 109 to 112. The coverage gate
  is measured over the whole bundle rather than per class (ADR-006), and the bundle's 80%
  instruction minimum is met with no exclusion; separately, each of the three new types is
  at 100% instruction and 100% branch coverage — `ScoredPlay` 145/145 instructions and
  12/12 branches, `Standing` 57/57 and 8/8, `ScoreSheet` 470/470 and 48/48 — so the bundle
  result is not carrying them and the 70% branch
  minimum ADR-006 holds in reserve would not trouble them either.
- The score is recomputed on every read, and that cost is accepted rather than mitigated.
  A six-player game plays 68 cards in 12 tricks and a three-player game 68 in 23, so the
  input is bounded by the deck and is small enough that measuring it would be theatre.
  *(Note: the deck reached 68 cards when EOP-75 removed the Aces — see [ADR-041](ADR-041-printed-deck-has-no-aces.md).)*
  Nothing here forecloses caching later if measurement ever contradicts that — but the
  honest position today is that this design trades a fixed, small, repeated cost for the
  removal of a whole class of drift bug, and the cost is real.
- Persisting the final standings is left open to the third slice as a projection, under
  the two conditions stated above. That is a loose end, not a settled design: if the
  conditions cannot be met the third slice persists nothing, and a reader should not treat
  a standings table as planned.
- No changeset, no entity change, no route and no use case in this slice. It is additive
  and unreachable from HTTP, so it is safe to merge with `eop.features.trick-play` still
  `false` — which it stayed at the time, per ADR-028; flipping it is a separate story.
  *(Amended 2026-08-21, EOP-49 — that separate story ran. EOP-70 set the flag `true` on
  2026-08-17, so "which it stays" is spent: what this slice added is reachable now. The
  bullet is left standing as the merge-safety record it is, because the reasoning was
  sound and the merge was safe; only the containment in front of it has gone.
  [ADR-013](ADR-013-feature-flags.md) states the live value.)*
- The reading slice needs one new read method on `TrickRepository` returning a session's
  tricks. It must carry no acting player, per ADR-024: authorising the requester is the
  use case's job and no port's.
- Two known debts were raised in review of this slice and left for the second, both
  harmless only while nothing could reach this code over HTTP. **The first is now
  discharged and the second is not**, and they are recorded separately below so that
  closing one cannot be read as closing both. First, the
  refusals in `ScoreSheet.of`, `ScoreSheet.pointsOf` and `ScoredPlay.of` throw
  `IllegalArgumentException` with
  an identifier — a `playerId`, `trickPlayId` or `trickId` — interpolated into the message,
  except the empty-players and duplicate-sequence refusals, which name no identifier at
  all. The house
  style is a named domain exception carrying typed fields — which exists precisely so that
  a boundary can refuse without echoing an identifier into a Problem Details body. Those
  had to become named types before a route could reach them, and `pointsOf` was the one to
  start from: it is the only one of the three a reading route calls with a client-supplied
  identifier, so it was the first that could echo one into a response. **Discharged by the
  second slice**, which is the slice that made them reachable: all eight became
  `ScoreNotDerivableException` carrying a typed reason, mapped to 500 with a body that names
  no identifier ([ADR-031](ADR-031-the-score-is-read-through-its-own-route.md)). Second, `ScoredPlay`'s canonical
  constructor accepts `components` and `notes` as raw strings with no length or
  control-character bound; today every value on the shipped path has already passed
  `TrickPlay`'s validation, so the guarantee rests on `ScoredPlay.of` being the only
  caller rather than on the type. Neither was a defect in this slice. The first is closed; **the second is
  still open**, and it closes when any path builds a `ScoredPlay` from something other than a
  `TrickPlay` that has already been revalidated on the way out of the database.
- One documentation claim was falsified by this slice itself and is corrected within the
  same slice, before the branch merges: `TrickPlay`'s Javadoc said that scoring "decides
  what it does with a linked
  threat that names nothing", and this slice decided it. It now records the decision in
  the past tense and cites this ADR. A javadoc that poses a question this slice answered
  is the one kind of staleness that cannot be deferred.
- Two further claims are stale or will go stale, and must be corrected at the claim rather
  than pointed at from elsewhere. `SessionStatus`'s class Javadoc is **already** wrong, and
  not because of this slice: it explains that nothing advances a session out of
  `IN_PROGRESS` "because playing cards arrives with EOP-14", and playing cards has
  arrived — only the consequent still holds, and it holds until the third slice. The
  `eop.features.trick-play` comment block in `application.yml`, which names EOP-15 as an
  outstanding blocker, was entirely true after this slice, and went stale on the **second**
  rather than the third: a score can now be read, so the clause claiming there was none
  anywhere was rewritten where it sat, not pointed at from here
  ([ADR-031](ADR-031-the-score-is-read-through-its-own-route.md)). `TrickState.handComplete`'s "says nothing about the score" stays true and stays as
  written — the flag genuinely says nothing about the score; it is the sheet that says it.
- ADR-018 predicted that "EOP-15 adds scoring rows". This decision adds none, and may never
  add any. That prediction should be read as superseded here rather than as a plan.
- Richer scoring and the beginner relaxation are now cheap to add and deliberately
  absent. Anyone adding either should expect to defend it against the reason the author
  removed it, which no longer applies to a server and did apply to a table.

## Related

- [ADR-004](ADR-004-api-contract-first.md) — the score route in the second slice is
  hand-authored into `docs/api/openapi.yml` before its controller exists.
- [ADR-006](ADR-006-build-quality-gates.md) — the coverage gate is a bundle
  measurement, not a per-class one, which is what the first consequence above states
  precisely rather than approximately.
- [ADR-015](ADR-015-player-identity.md) — display names are unverified and not unique,
  which is why nothing here keys on one.
- [ADR-018](ADR-018-uuid-v7-identifiers.md) — predicted scoring rows this decision does
  not create; see the consequence above.
- [ADR-020](ADR-020-session-concurrency-control.md) — the conditional-update guard the
  later slices must use for the `COMPLETED` transition; a derived score adds no state for
  it to protect.
- [ADR-023](ADR-023-deal-remainder-and-turn-order.md) — the uneven deal and the short
  final trick, which is why the sheet counts plays rather than assuming a trick size.
- [ADR-024](ADR-024-trick-play-persistence-boundary.md) — no port method takes an acting
  player, binding on the read method the second slice adds.
- [ADR-026](ADR-026-use-case-observability.md) — the second slice adds a use case, and is
  where that ADR's logging obligations attach to scoring.
- [ADR-027](ADR-027-singleton-subresource-naming.md) — cited for its **prohibition**, not
  merely its naming rule. `/hand` is singular because a collection of every hand must
  never exist; a score sheet is by construction every player's rows, so the second slice
  must argue explicitly why that prohibition is not breached. The argument available to it
  is that ADR-027 forbids exposing every player's *private* state through one route, and a
  score names only cards already face up.
- [ADR-028](ADR-028-end-of-hand-without-release-or-score.md) — assigned the `COMPLETED`
  transition to this story and kept `eop.features.trick-play` `false` (EOP-70 has since set
  it `true` — 2026-08-17; see [ADR-013](ADR-013-feature-flags.md)). That transition
  amends ADR-028 in the third slice rather than earning a new ADR, since ADR-028 already
  owns the decision.
