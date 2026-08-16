# ADR-039: New-Game Reset — `COMPLETED → IN_PROGRESS`

**Status:** Accepted  
**Date:** 2026-08-16  
**Deciders:** @tech-lead, @architecture-guardian  
**Story:** EOP-65

---

## Context

EOP-65 adds a facilitator "Start new game" button that re-deals the same 78-card deck to
the same seated players without creating a new session. This requires a new session-status
transition: `COMPLETED → IN_PROGRESS`.

ADR-032 documents the two existing paths to `COMPLETED` (automatic last-trick resolution
and facilitator early end) and the `recordCompleted` compare-and-swap. It does not cover
any path back from `COMPLETED`, because no such path existed at the time.

Several design questions arose:

- Where does the reset live?
- Is the reset atomic?
- What happens to the persisted `game_result` rows?
- Does the leaderboard derive scores from stored data or from tricks?

---

## Decision

### 1. `NewGameUseCase` owns the reset

A new `NewGameUseCase` (facilitator-only, gated on `eop.features.game-over`) performs the
reset in four steps:

1. `TrickRepository.clearTricksForNewGame(sessionId)` — deletes trick-play-components,
   trick-plays, and tricks in FK-safe order.
2. `HandRepository.clearHandsForNewGame(sessionId)` — deletes hand-cards and hands in
   FK-safe order.
3. `SessionRepository.resetToInProgress(sessionId, now)` — compare-and-swap:
   `UPDATE … SET status = 'IN_PROGRESS', current_leader_seat = NULL WHERE status = 'COMPLETED'`.
   Zero rows updated → `SessionNotInProgressException` (409).
4. Re-deal via the same logic as `DealHandsUseCase`.

### 2. The reset is not atomic

Steps 1–4 are separate port calls, each in its own `@Transactional` adapter method. A
partial failure (e.g. crash between steps 2 and 3) leaves the session in an inconsistent
state: tricks and hands cleared but status still `COMPLETED`, or status reset but no hands
dealt.

**Accepted trade-off.** The facilitator can retry: the delete operations are idempotent
(deleting already-absent rows is a no-op), and the compare-and-swap at step 3 prevents a
double-reset. A crash between steps 3 and 4 leaves the session `IN_PROGRESS` with no
hands; the facilitator can call the new-game endpoint again to re-deal.

A single atomic operation would require either a stored procedure (rejected — no stored
procedures in this codebase) or a distributed saga (rejected — over-engineering for a
facilitator-only action that is inherently low-frequency and self-correcting on retry).

### 3. `SessionRepository.resetToInProgress` is compare-and-swap

Mirrors the `recordCompleted` pattern (ADR-020, ADR-032 §3). The JPQL update:

```sql
UPDATE GameSessionJpaEntity s
SET s.status = 'IN_PROGRESS', s.currentLeaderSeat = NULL, s.updatedAt = :now
WHERE s.id = :sessionId AND s.status = 'COMPLETED'
```

Zero rows updated → `noLongerInProgress(sessionId)` helper reads the row and returns
either `SessionNotFoundException` (404) or `SessionNotInProgressException` (409).

### 4. Persisted `game_result` rows are not cleared on reset

The `game_result` and `game_result_player` rows written by `PersistGameResultUseCase` are
retained after a new-game reset. They are historical records of the completed game and are
not used to answer the leaderboard (see §5). Retaining them avoids a destructive delete
that could not be undone if the reset fails partway through.

A unique constraint on `game_result.game_session_id` is intentionally absent: a session
that completes, resets, and completes again will accumulate multiple result rows. The
`GameResultRepository.findBySessionId` port returns `Optional<GameResult>` and resolves to
the most-recently-inserted row via the natural insertion order of the Spring Data query.
If a future story needs to distinguish results across resets, a `reset_count` or
`finalised_at` ordering can be added then.

### 5. Leaderboard scores are always derived from tricks, never from stored data (ADR-030)

`GetLeaderboardUseCase` re-reads the tricks at request time and builds a fresh `ScoreSheet`.
`LeaderboardDto.from(scoreSheet, status)` uses `scoreSheet.standings()` for scores,
positions and the `tied` flag. The `GameResult` returned by `GameResultRepository` is used
only to confirm that a result exists (i.e. the game has been finalised); its stored
standings are placeholder values and are never read to answer the leaderboard.

This satisfies ADR-030 condition 2: "a persisted standing must never be read back to
answer the score". After a new-game reset the tricks are cleared, so the leaderboard for
the new game starts from zero — the stored result rows from the previous game are invisible
to the leaderboard endpoint.

### 6. `PersistGameResultUseCase` is called by `ResolveTrickUseCase`

`ResolveTrickUseCase` already owns the `COMPLETED` transition (ADR-032 §1). After calling
`SessionRepository.recordCompleted`, it calls `PersistGameResultUseCase.execute(sessionId)`
via an `Optional<PersistGameResultUseCase>` dependency. The `Optional` is injected by
Spring: when `eop.features.game-over` is off the bean does not exist and the optional is
empty; when the flag is on the bean is present and the result is persisted.

Persistence is best-effort: a `RuntimeException` from `PersistGameResultUseCase` is caught
and swallowed so that a persistence failure does not roll back the trick resolution that
has already been committed. The leaderboard will return 404 until the result is persisted,
which is the correct behaviour for a transient failure.

---

## Consequences

- `SessionStatus.COMPLETED` is no longer a terminal state; it can transition back to
  `IN_PROGRESS` via `NewGameUseCase`.
- `SessionRepository` gains a `resetToInProgress(UUID, Instant)` port method.
- `HandRepository` gains `clearHandsForNewGame(UUID)`.
- `TrickRepository` gains `clearTricksForNewGame(UUID)`.
- `ResolveTrickUseCase` gains an `Optional<PersistGameResultUseCase>` dependency.
- The `game_result` table may accumulate multiple rows per session (one per completed game).
- ADR-032 §2 ("No new database column") is not violated: `game_result.started_at` and
  `finalised_at` are columns on a new table, not on `game_session`.

---

## Alternatives Considered

### A. Atomic reset via a single stored procedure

Rejected. No stored procedures in this codebase. The retry-safe design is sufficient for
a facilitator-only, low-frequency action.

### B. Delete `game_result` rows on reset

Rejected. Historical results are valuable and the delete would be irreversible. The
leaderboard derives scores from tricks, so stale result rows do not affect correctness.

### C. `NewGameUseCase` delegates to `DealHandsUseCase`

Rejected. `DealHandsUseCase` checks `actingPlayer.canStartPlay()` (facilitator role) and
calls `handRepository.recordDeal()`. The new-game path needs to clear existing data first,
which `DealHandsUseCase` does not do. Delegating would require either modifying
`DealHandsUseCase` (breaking its single responsibility) or calling it after the clear
(which duplicates the facilitator check). `NewGameUseCase` inlines the deal logic instead.

---

## Related

- [ADR-020](ADR-020-session-concurrency-control.md) — compare-and-set on `status`.
- [ADR-030](ADR-030-scoring-is-derived-not-accumulated.md) — scores are derived from
  tricks; persisted standings are never read back to answer the score.
- [ADR-032](ADR-032-end-of-game-transitions.md) — the two existing paths to `COMPLETED`;
  this ADR adds the path back from `COMPLETED`.
- [ADR-013](ADR-013-feature-flags.md) — `eop.features.game-over` gates both
  `GameOverController` and the three new use-case beans.
