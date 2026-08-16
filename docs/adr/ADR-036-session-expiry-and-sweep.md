# ADR-036: Session Expiry and Abandoned-Session Sweep

**Status:** Accepted (EOP-22, 2026-08-16)

**Date:** 2026-08-16

**Deciders:** Miguel González

## Context

ADR-019 explicitly deferred session expiry: "Rows accumulate. At the volume this application will
see, a cleanup job is speculative work; `ABANDONED` exists in the status enum so that the concept
has somewhere to live when it is needed." EOP-22 is the story that needs it.

The immediate driver is credential lifetime. A player token issued at join time has no expiry today.
A token that never expires is a permanent credential: a player who leaves a session and discards
their tab still holds a token that can be replayed indefinitely. The session itself is the natural
lifetime boundary — once a session is gone, its tokens should be gone with it.

Two mechanisms are needed:

1. **An expiry guard** — reject a `resolve-player` call against a session whose `expires_at` has
   passed, so that a stale token cannot be used to act in a session that has outlived its intended
   window.
2. **A sweep** — periodically find sessions that have passed their `expires_at` and delete them,
   so that the database does not accumulate rows indefinitely.

### What ADR-019 decided and why this reverses it

ADR-019 declined expiry on the grounds that it was speculative work at the expected volume. That
reasoning was correct at the time: the application had no production traffic, no credential-lifetime
concern had been raised, and adding a sweep without a driver would have been premature. EOP-22
provides the driver — the credential-lifetime gap — and the volume argument is unchanged, so the
reversal is about the driver, not the volume.

### Constraints inherited from the rest of the architecture

- **Single-instance deployment** (ADR-012, ADR-016). The sweep runs in one JVM. There is no
  distributed lock, no ShedLock, no leader election. This is an accepted limitation: the application
  is deployed as a single container, and the sweep is designed for that topology. A multi-instance
  deployment would require a coordination mechanism not present here.
- **Feature flag** (ADR-013). The sweep is gated on `eop.features.session-lifecycle`. The expiry
  guard in `ResolvePlayerUseCase` is **not** gated, for the reason given below.
- **No `Authorization: Bearer` challenge** (ADR-015). The API authenticates with a custom
  `X-EoP-Player-Token` header. A `WWW-Authenticate` header on a 401 would advertise a scheme the
  API never reads and instruct conforming clients to send their token in a header nothing consumes.
  The expiry response is therefore **403 Forbidden**, consistent with the existing `Forbidden`
  component in `docs/api/openapi.yml` and with ADR-015's rationale.

## Decision

### 1. TTL is 24 hours, fixed in the domain

`GameSession.SESSION_TTL = Duration.ofHours(24)`. The value is a domain constant, not a
configuration property, for the same reason `MAXIMUM_PLAYERS` and `MINIMUM_PLAYERS_TO_START` are
domain constants: it comes from the game's intended use (a facilitated workshop session), not from
operations. A workshop that runs longer than 24 hours is not the target use case.

The TTL is set once, at `openLobby()`, and never renewed. A session that is still in progress at
hour 24 will begin returning 403 on the next `resolve-player` call. This is a deliberate choice:
renewing the TTL on activity would require removing the `updatable = false` constraint on
`expires_at` (and a corresponding schema change to allow updates) and
would make the expiry window unbounded for an active session. The 24-hour window is generous enough
that a normal workshop session will complete well within it.

### 2. `expires_at` is owned by the domain, set by the application JVM

`GameSession.openLobby()` computes `expiresAt = now.plus(SESSION_TTL)` using an injected `Clock`.
The database column carries a `defaultValueComputed` expression (`NOW() + INTERVAL '24 hours'` on
PostgreSQL, `CURRENT_TIMESTAMP + INTERVAL '24' HOUR` on H2) as a backfill safety net for any row
inserted without going through the domain — for example, a test fixture that inserts directly via
JDBC. The domain is the authority; the database default is a fallback.

**Known limitation:** the application JVM clock and the database clock are two independent
authorities. Under clock skew, sessions may expire slightly earlier or later than intended. At the
24-hour granularity of this TTL, a skew of seconds or even minutes is inconsequential.

### 3. The expiry guard is ungated

`ResolvePlayerUseCase` checks `session.expiresAt().isBefore(Instant.now(clock))` before the token
check. This guard is **not** behind `eop.features.session-lifecycle`. The rationale:

- `ResolvePlayerUseCase` is a shared chokepoint used by both the session-lifecycle flag and the
  trick-play flag. Gating it on `session-lifecycle` would mean the guard is absent when only
  `trick-play` is on.
- The guard is a read-only check. It does not write, does not delete, and does not change any state.
  It is safe to run even when the sweep is not running.
- The 24-hour TTL is generous. When both flags are off, no session is created
  through the API, so no session can expire. The guard is a no-op in that state.
  As of EOP-25 (2026-08-16), `eop.features.session-lifecycle` is on by default, so sessions are
  created and the guard is active in the default configuration.

`application.yml` documents this explicitly in the flag's comment block.

### 4. The sweep is gated on `eop.features.session-lifecycle`

`ExpiredSessionSweepScheduler` carries `@ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")`.
The sweep is the destructive half of expiry: it deletes rows. Gating it on the same flag as the
session-lifecycle endpoints means the sweep shares the flag's lifecycle: when the flag is off the
sweep is absent; when the flag is on the sweep is active. As of EOP-25 (2026-08-16),
`eop.features.session-lifecycle` is on by default, so the sweep is active in the default
configuration (cadence: 1 h fixed delay, 5 min initial delay).

The sweep runs on a fixed delay (default 1 hour, configurable via `eop.sweep.interval-ms`) with an
initial delay (default 5 minutes, configurable via `eop.sweep.initial-delay-ms`). Both are bound
through `SweepProperties` (`@ConfigurationProperties(prefix = "eop.sweep")`) with validation.

### 5. The sweep delegates to `SweepExpiredSessionsUseCase`

The scheduler is a thin adapter. The sweep policy — what "expired" means, which sessions to select,
how to handle per-session failures — lives in `SweepExpiredSessionsUseCase` in the use-case layer.
This keeps the policy testable without Spring and visible to anyone reading the use-case package.

### 6. `abandonAndDelete` is unconditional

`SessionRepository.abandonAndDelete(UUID sessionId)` marks the session `ABANDONED` and then deletes
it in a single transaction. The `ABANDONED` write is not a compare-and-set: it has no status
predicate. The net committed effect is the delete — no row survives carrying `ABANDONED`, because
the mark and the delete share a transaction.

The `ABANDONED` status write exists to satisfy the `@Version` increment (optimistic locking
bookkeeping) and to make the intent of the operation legible in the JPQL. It is not an audit trail:
no observer can see the `ABANDONED` state, because the row is deleted in the same transaction.

The only guard against arbitrary deletion is the caller: `SweepExpiredSessionsUseCase` queries
`findExpiredSessionIds(Instant.now(clock))` and passes only those IDs to `abandonAndDelete`. The
port javadoc states this explicitly.

**`findExpiredSessionIds` includes `AND s.status <> 'ABANDONED'` as defence-in-depth.** Under
normal operation no committed row can carry `ABANDONED` (mark and delete share a transaction), so
the predicate is inert for application-produced rows. It exists as a safety net against out-of-band
writes (e.g. a direct SQL insert in a test fixture or a future migration that pre-populates the
column). The integration test `shouldExcludeAlreadyAbandonedSessions` exercises this path via raw
JDBC to verify the predicate fires when the state is reachable through that channel.

### 7. The expiry response is 403, not 401

`SessionExpiredException` maps to `403 Forbidden` in `GlobalExceptionHandler`. The `Forbidden`
component in `docs/api/openapi.yml` is updated to include session expiry as a 403 case, with the
`title` field distinguishing `"Player not recognised"` from `"Session expired"`.

A 401 is not used because:
- RFC 9110 §15.5.2 requires a 401 to carry a `WWW-Authenticate` header naming the challenge scheme.
- This API authenticates with `X-EoP-Player-Token`, not `Authorization: Bearer`. Emitting
  `WWW-Authenticate: Bearer` would advertise a scheme the API never reads (ADR-015).
- The existing `Forbidden` component already documents this reasoning at `openapi.yml:1635-1646`.

### 8. The flag covers the sweep, not the guard

When `eop.features.session-lifecycle` is `false`:
- `SessionController` is absent (ADR-013, EOP-48).
- `ExpiredSessionSweepScheduler` is absent.
- `SweepExpiredSessionsUseCase` is absent.
- `ResolvePlayerUseCase` is present and its expiry guard is active — but since no session can be
  created through the API, no session can expire, and the guard is a no-op.

When `eop.features.session-lifecycle` is `true` (the default as of EOP-25, 2026-08-16):
- All of the above are present.
- Sessions are created with a 24-hour TTL.
- The sweep runs hourly (by default) and deletes expired sessions.
- The guard rejects `resolve-player` calls against expired sessions with 403.

### 9. Flag removal

Per ADR-013, the flag is removed one release after full rollout. Removing this flag makes the sweep
unconditional and permanent. Before removing it, verify that:
- The sweep cadence (`eop.sweep.interval-ms`) is appropriate for the production load.
- Any environment override of `EOP_FEATURES_SESSION_LIFECYCLE` is removed from the deployment
  configuration, because an override set for one meaning silently governs whatever the flag comes
  to mean later (ADR-013).

## Consequences

**Positive — credential lifetime is bounded.** A player token is valid for at most 24 hours after
the session was opened. After that, `resolve-player` returns 403 and the token cannot be used.

**Positive — the database does not accumulate rows indefinitely.** The sweep deletes expired
sessions (and their players, hands, tricks and plays, via `ON DELETE CASCADE`) on a configurable
schedule.

**Negative — sessions cannot be extended.** The TTL is fixed at creation and `expires_at` is
`updatable = false`. A workshop that runs longer than 24 hours will begin returning 403 at hour 24.
This is a known limitation accepted in exchange for simplicity.

**Negative — the sweep is single-instance only.** Two replicas sweeping the same ID set
concurrently would both attempt to delete the same rows; the second attempt is silently ignored —
`deleteById` on a missing row is a no-op (`findById(id).ifPresent(this::delete)`), so no exception
is thrown and no warning is logged. The application is deployed as a single container (ADR-012,
ADR-016), so this is not a current concern, but it is an unstated precondition for any future
multi-instance deployment.

**Negative — the `ABANDONED` write is unobservable.** The mark and the delete share a transaction,
so no row ever persists in the `ABANDONED` state. If an audit trail of abandoned sessions is needed
in the future, a separate audit table or event log will be required.

**Neutral — the expiry guard is ungated.** `ResolvePlayerUseCase` checks expiry regardless of the
flag position. As of EOP-25 (2026-08-16), `eop.features.session-lifecycle` is on by default, so
sessions are created through the API in the default configuration and the guard is live. Before
EOP-25, when the flag was off, no session could be created and the guard was a no-op. This is
documented in `application.yml`.

**Neutral — two clock authorities.** The domain sets `expires_at` using the application JVM clock;
the database default expression uses the database clock. At the 24-hour TTL granularity, any
realistic clock skew is inconsequential.

**Accepted limitation — expiry is enforced at the `ResolvePlayerUseCase` boundary only.** The
`JoinSessionUseCase` does not check `session.expiresAt()`. A player can join an expired `LOBBY`
session via join code and be issued a fresh identity token. The token will be immediately rejected
by `ResolvePlayerUseCase` on the next call, so the window of misuse is narrow — but the join itself
succeeds. Closing this gap requires adding an expiry guard to `JoinSessionUseCase` (both operands
are already available: `session.expiresAt()` and the injected `Clock`). This is deferred to a
future story; the accepted risk is that a player can join an expired session and receive a token
that is immediately unusable.

## Related

- [ADR-019](ADR-019-session-lifecycle-and-join-codes.md) — the decision this reverses; its
  Consequences section is amended to point here
- [ADR-013](ADR-013-feature-flags.md) — the flag mechanism; amended to record the sweep flag and
  its scope
- [ADR-015](ADR-015-player-identity.md) — the opaque token and why `Authorization: Bearer` is not
  used
- [ADR-005](ADR-005-error-handling-strategy.md) — RFC 9457 problem details and the single
  `@ControllerAdvice`
- [ADR-008](ADR-008-database-migration-liquibase.md) — Liquibase changeset `006` adds `expires_at`
- [ADR-012](ADR-012-deployment-target.md) — single-instance deployment; the sweep's single-instance
  assumption
- [ADR-016](ADR-016-local-container-runtime.md) — the local container stack
- [ADR-020](ADR-020-session-concurrency-control.md) — compare-and-set on `status`; `abandonAndDelete`
  is deliberately not a CAS
