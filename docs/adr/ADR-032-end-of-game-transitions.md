# ADR-032: End-of-Game Transitions

**Status:** Accepted  
**Date:** 2026-08-15  
**Deciders:** @tech-lead, @architecture-guardian  
**Story:** EOP-15 Slice C

---

## Context

EOP-15 Slice A established pure-domain scoring. Slice B exposed the score via
`GET /api/v1/sessions/{sessionId}/score`. Slice C closes the loop: a session
must eventually reach `COMPLETED` so that clients know the game is over and
the score is final.

Two paths lead to `COMPLETED`:

1. **Automatic** — the last trick is resolved and no cards remain in any hand.
2. **Facilitator early end** — the facilitator calls
   `POST /api/v1/sessions/{sessionId}/end` before all cards are played.

Several design questions arose:

- Where does the automatic transition live?
- Does `COMPLETED` require a new database column?
- Who may trigger the early end?
- What happens to the score when the game ends early?

---

## Decision

### 1. Automatic transition lives in `ResolveTrickUseCase`

After `TrickRepository.recordResolution` writes the resolved trick, the use
case checks whether `nextLeaderSeat` is empty. An empty `nextLeaderSeat` means
every card has been played and no further trick can open. At that point the use
case calls `SessionRepository.recordCompleted` and publishes `GAME_COMPLETED`.

This keeps the transition co-located with the event that causes it. The
alternative — a separate scheduled job or a domain event listener — would
introduce asynchrony and a window in which the session is `IN_PROGRESS` but
the hand is over, which is a lie the read model would have to paper over.

### 2. No new database column

`updated_at` on the session row is updated by `advanceStatus` and serves as
the completion timestamp. A dedicated `completed_at` column would duplicate
that information and require a migration for no additional query capability.
If a future story needs to filter sessions by completion time, the column can
be added then with a migration that back-fills it from `updated_at`.

### 3. `SessionRepository.recordCompleted` is compare-and-swap

The method advances the status from `IN_PROGRESS` to `COMPLETED` in a single conditional
`UPDATE`. If zero rows are updated it reads the row to distinguish:

- **Gone** → `SessionNotFoundException` (404)
- **Not `IN_PROGRESS`** → `SessionNotInProgressException` (409)

This mirrors the `recordStarted` pattern (ADR-020) and prevents double-complete
races from silently succeeding.

**Two-transaction race on the automatic path.** `ResolveTrickUseCase` calls
`TrickRepository.recordResolution` and `SessionRepository.recordCompleted` in two
separate transactions — each adapter method carries its own `@Transactional` and the
use case is a plain class, not a Spring proxy. If a facilitator's `POST /end` wins the
`advanceStatus` race in the window between the first commit and the second, the
`recordCompleted` call finds zero rows and would throw `SessionNotInProgressException`.
The session is already `COMPLETED` — the desired outcome — so the auto-complete branch
catches that exception and treats it as success rather than propagating a 409 for a
trick resolution that has already been durably committed.

### 4. Facilitator-only early end

Only the facilitator may call `POST /api/v1/sessions/{sessionId}/end`. Any
seated player may resolve a trick (the outcome is deterministic), but ending
the game early is a consequential decision that belongs to the person who
created the session.

The use case (`EndSessionUseCase`) resolves the caller's credential to a
player, verifies they are the facilitator, then calls `GameSession.complete`
and `SessionRepository.recordCompleted`.

### 5. Score is still readable after early end

`GET /api/v1/sessions/{sessionId}/score` derives the score from the tricks
already recorded. It does not check session status. A `COMPLETED` session
therefore returns the score of the plays made, which may be a partial game.
This is intentional: the facilitator ends early because the group has reached
a conclusion, not because the score is meaningless.

### 6. `GAME_COMPLETED` event

A new `SessionEventType.GAME_COMPLETED` (`"game-completed"`) is published on
both paths. Clients streaming `GET /api/v1/sessions/{sessionId}/events` receive
it and know to re-read the session state. The event carries no payload beyond
the type and session identifier, consistent with ADR-014.

### 7. `EndSessionController` is gated by `eop.features.trick-play`

The early-end route is part of the trick-play feature: it is only meaningful
once a hand is in progress. Gating it on the same flag as `TrickController`
keeps the feature boundary clean and avoids a route that can be called but
always returns 409.

---

## Consequences

- `SessionStatus.COMPLETED` is now reachable in production code, not just
  declared in the enum.
- `ResolveTrickUseCase` gains a `SessionRepository` dependency. Its bean
  definition in `UseCaseConfiguration` is updated accordingly.
- `GlobalExceptionHandler` maps `SessionNotInProgressException` → 409, with
  the title "Session is not in progress".
- The score endpoint remains status-agnostic: it answers for `IN_PROGRESS` and
  `COMPLETED` sessions alike.
- ADR-028 ("end of hand without release or score") is superseded for the
  automatic path: the hand ending now triggers the `COMPLETED` transition
  directly rather than leaving the session in `IN_PROGRESS` indefinitely.

---

## Alternatives Considered

### A. Separate `CompleteSessionUseCase` for the automatic path

Rejected. The automatic transition is a direct consequence of the last trick
resolving. Splitting it into a second use case would require either a domain
event bus (not yet in the architecture) or a synchronous call from
`ResolveTrickUseCase` to `CompleteSessionUseCase`, which is the same coupling
with an extra indirection.

### B. `completed_at` column

Rejected for now. `updated_at` is sufficient and a migration can add the
column if a future query needs it. Adding it speculatively would require a
changeset, a rollback, and a Hibernate mapping for a field nothing reads.

### C. Any seated player may end early

Rejected. Ending early is a consequential, irreversible action. Restricting it
to the facilitator matches the pattern established for `start` and `deal`, and
avoids a situation where one player ends the game before others are ready.

---

## Related

- [ADR-013](ADR-013-feature-flags.md) — `@ConditionalOnProperty` as the flag mechanism,
  and why the off position is tested with bean-absence assertions as well as 404s.
- [ADR-014](ADR-014-realtime-transport.md) — state-free events and recovery by re-reading,
  which is why `GAME_COMPLETED` carries no payload beyond the session identifier.
- [ADR-020](ADR-020-session-concurrency-control.md) — compare-and-set on `status`, the
  pattern `recordCompleted` follows.
- [ADR-026](ADR-026-use-case-observability.md) — still `Proposed`; `EndSessionUseCase`
  logs nothing, consistent with every other use case.
- [ADR-028](ADR-028-end-of-hand-without-release-or-score.md) — superseded for the
  automatic path: the hand ending now triggers the `COMPLETED` transition directly.
- [ADR-030](ADR-030-scoring-is-derived-not-accumulated.md) — the score is derived from
  plays; it remains readable after an early end, returning the score of the plays made.
- [ADR-031](ADR-031-the-score-is-read-through-its-own-route.md) — the score route is
  status-agnostic; it answers for both `IN_PROGRESS` and `COMPLETED` sessions.
- [ADR-039](ADR-039-new-game-reset.md) — adds the `COMPLETED → IN_PROGRESS` reset path
  introduced by EOP-65, which this ADR does not cover.
