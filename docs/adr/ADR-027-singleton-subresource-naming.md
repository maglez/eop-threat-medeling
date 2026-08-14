# ADR-027: A Singleton Sub-Resource May Be Singular, and `/hands` Is Forbidden

**Status:** Accepted
**Date:** 2026-08-14
**Deciders:** @architecture-guardian, @tech-lead

## Context

`.opencode/rules/api-design.md` is a binding project rule and states the URL pattern as
`/api/v1/{resource}` **with plural nouns**. Every path in `docs/api/openapi.yml` obeys it:
`/api/v1/cards`, `/api/v1/cards/{cardId}`, `/api/v1/sessions`, `/api/v1/sessions/{joinCode}/players`,
`/api/v1/sessions/{sessionId}/plays`.

EOP-14 Slice D added a route that does not: **`GET /api/v1/sessions/{sessionId}/hand`**, singular.

The reason is not brevity or taste. A hand is secret for the whole of a game, and the security
property this system most needs to hold is that no caller can ever read another player's cards. The
route returns exactly one thing — the hand belonging to the player the request's identity token
resolves to — and it takes no parameter that could select a different one. `ReadOwnHandUseCase.execute`
takes `(sessionId, playerToken)` and derives the seat from the resolved player; there is no seat
argument to supply and no sibling use case that returns anybody else's hand or all of them.

`/hands` would be a plural noun naming a collection. A collection resource carries an implicit promise
that it can be listed, and listing every hand at the table is precisely the operation that must never
exist. The plural would advertise it, and the first reader to assume the advertisement was honest would
either write the handler or file a bug that the collection route is missing.

The reasoning was recorded in the path description in `docs/api/openapi.yml` and in
`TrickController`'s javadoc. `@architecture-guardian` rejected that as insufficient during the Slice D
Definition-of-Done review: a javadoc is invisible to the next agent, who will read the rule file rather
than the controller, and the two live outcomes of an unrecorded exception are both bad — somebody
"fixes" `/hand` to `/hands` for consistency and re-opens the hole, or somebody cites `/hand` as
precedent for singular paths in general.

## Decision

**A resource that is a singleton *by construction* may be named with a singular noun.** The test is not
"is there one of these right now" but "can this path ever address more than one, for any caller". A
path that resolves its subject entirely from the requester's identity, with no selector in the URL, the
query string or the body, is a singleton by construction and takes the singular.

`GET /api/v1/sessions/{sessionId}/hand` is the first and currently the only such path.

**`GET /api/v1/sessions/{sessionId}/hands` is forbidden, not merely unused.** So is any route, use
case, port method or DTO that returns a hand the caller does not hold. This is the substantive half of
the decision; the naming is downstream of it. `HandDto` exists, and no `HandsDto`, `HandPage` or
`HandCollection` may be added.

**Plural remains the default and the rule.** `api-design.md` is unamended. This ADR is a named
exception with a stated test, not a licence for singular paths generally. A collection resource — one
that can address more than one member, even if it happens to hold one today — stays plural:
`/players` is plural although a lobby may hold a single player, because `/players/{playerId}` is
addressable and listing them is legitimate.

## Consequences

- One documented departure from a binding rule, with a test a future author can apply rather than a
  precedent they must guess at.
- The prohibition is now recorded somewhere a reviewer will look. `api-design.md` says plural; a
  reader who finds `/hand` and wonders why has this file to find, and this file says why `/hands` is
  not a missing feature.
- Nothing mechanically enforces the prohibition. There is no test that fails if somebody adds
  `/hands`, because the way to enforce "this route must never exist" is a review, not an assertion.
  What partially covers it is that adding such a route would need a new port method or a new use case,
  and both are conspicuous in a diff. This is the same weakness ADR-026 records about itself, and it is
  the reason the decision is written down rather than left implicit.
- `TrickDto` and `HandDto` deliberately publish less than a client needs to play a full hand
  unaided — see [ADR-013](ADR-013-feature-flags.md) for what `eop.features.trick-play` still withholds
  and why the flag cannot be turned on until a later slice adds a read exposing whose turn it is. That
  is a separate limitation and is not what this ADR is about.
  *(Amended 2026-08-14, EOP-14 Slice E — the sentence above is kept as written because it records the
  position on the day this ADR was accepted, but the clause "until a later slice adds a read exposing
  whose turn it is" is spent: **this ADR named the route that slice would use, and Slice E built it.**
  `GET /api/v1/sessions/{sessionId}/tricks/current` now answers with `seatToPlay`, `complete`,
  `nextLeaderSeat` and `handComplete` (`TrickStateDto.java:38-41`), so `TrickDto`'s omissions no longer
  leave a client unable to play — they are simply not that DTO's job, which is the naming point this
  ADR is actually about. `eop.features.trick-play` still ships `false`
  (`src/main/resources/application.yml:99`), and [ADR-013](ADR-013-feature-flags.md) — the flag
  register — now records that the three remaining reasons are ADR-026, EOP-48 and EOP-15, not the
  missing read. Follow that link for the current answer rather than the clause above.)*

## Alternatives considered

- **Rename the route to `/hands` and obey the rule.** Rejected. It would make the contract advertise a
  collection the system must never serve, and it buys only surface consistency. The rule exists to make
  URLs predictable, and a plural that cannot be listed is less predictable, not more.
- **Return the hand from the existing session-state route** (`GET /api/v1/sessions/{sessionId}`),
  avoiding a new path and therefore the naming question. Rejected: that route is the single authority on
  *shared* state and is read by every player, so folding secret per-caller data into it would make one
  response mean different things to different readers, and any future cache or fan-out of it would be a
  disclosure bug waiting to happen.
- **Amend `api-design.md` to permit singular paths.** Rejected. Weakening a binding rule to accommodate
  one justified exception is how rules stop being binding; recording the exception with its test keeps
  the default intact.
- **Leave the reasoning in the javadoc and the OpenAPI description only.** Rejected by
  `@architecture-guardian` during the Slice D review, for the reasons in the Context above. Both of
  those places are still correct and stay; this ADR is the durable record they point at.

## Relations

- Departs from `.opencode/rules/api-design.md`, which remains binding for every other path.
- [ADR-004](ADR-004-api-contract-first.md) — the contract in `docs/api/openapi.yml` is authored before
  the controller, so the path name was a decision taken in the contract commit (`1fd5718`) rather than
  discovered while writing code.
- [ADR-015](ADR-015-player-identity.md) — the `X-EoP-Player-Token` header is what makes the route
  a singleton by construction; without an identity credential there would be nothing to resolve the
  hand from and a selector would be unavoidable.
- [ADR-013](ADR-013-feature-flags.md) — the route is withheld by `eop.features.trick-play` and answers
  404 while it is `false`.
