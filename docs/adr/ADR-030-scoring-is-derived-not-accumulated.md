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

The cost is real and accepted: the score of a long game is recomputed on every read. A
six-player game plays 78 cards in 13 tricks and a three-player game 78 in 26, so the
input is bounded by the deck and is small enough that measuring it would be theatre.
Nothing here forecloses caching later if measurement ever contradicts that.

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
  framework imports. The production class count rises from 109 to 112 and JaCoCo's 80%
  instruction minimum is met on all three with no exclusion.
- No changeset, no entity change, no route and no use case in this slice. It is additive
  and unreachable from HTTP, so it is safe to merge with `eop.features.trick-play` still
  `false` — which it stays, per ADR-028; flipping it is a separate story.
- The reading slice needs one new read method on `TrickRepository` returning a session's
  tricks. It must carry no acting player, per ADR-024: authorising the requester is the
  use case's job and no port's.
- Two documentation claims become stale the moment the later slices land and must be
  corrected at the claim rather than pointed at from elsewhere:
  `SessionStatus`'s class Javadoc, which says nothing advances a session out of
  `IN_PROGRESS`, and the `eop.features.trick-play` comment block in `application.yml`,
  which names EOP-15 as an outstanding blocker. `TrickState.handComplete`'s "says nothing
  about the score" stays true and stays as written — the flag genuinely says nothing about
  the score; it is the sheet that says it.
- Richer scoring and the beginner relaxation are now cheap to add and deliberately
  absent. Anyone adding either should expect to defend it against the reason the author
  removed it, which no longer applies to a server and did apply to a table.

## Related

- [ADR-015](ADR-015-player-identity.md) — display names are unverified and not unique,
  which is why nothing here keys on one.
- [ADR-020](ADR-020-session-concurrency-control.md) — the conditional-update guard the
  later slices must use for the `COMPLETED` transition; a derived score adds no state for
  it to protect.
- [ADR-023](ADR-023-deal-remainder-and-turn-order.md) — the uneven deal and the short
  final trick, which is why the sheet counts plays rather than assuming a trick size.
- [ADR-024](ADR-024-trick-play-persistence-boundary.md) — no port method takes an acting
  player, binding on the read method the second slice adds.
- [ADR-027](ADR-027-singleton-subresource-naming.md) — the naming precedent for the score
  read the second slice adds.
- [ADR-028](ADR-028-end-of-hand-without-release-or-score.md) — assigned the `COMPLETED`
  transition to this story and keeps `eop.features.trick-play` `false`.
