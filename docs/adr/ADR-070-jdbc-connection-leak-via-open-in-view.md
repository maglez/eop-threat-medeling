# ADR-070: JDBC Connection Leak via Open Entity Manager in View

**Status:** Accepted
**Date:** 2026-09-06
**Deciders:** @tech-lead, @architecture-guardian
**Story:** EOP-227

## Context

Every request that touches the database stalled for up to a full 15 seconds, and the stalled requests were released together in batches on a 15-second grid. Measured at the Caddy proxy across one E2E suite run: 15 non-`/events` requests exceeded one second, the slowest at 15.004 s, with eight clustered at ~15 s. Affected endpoints included `GET /api/v1/sessions/{id}`, `GET .../hand`, `GET .../tricks/current`, `POST .../plays`, `POST .../start` and `POST /api/v1/sessions/{joinCode}/players`.

The root cause is one JDBC connection leaked per open SSE stream.

`GET /api/v1/sessions/{id}/events` (`SessionStreamController.streamSessionEvents`) validates the player token by calling `ResolvePlayerUseCase.execute`, which reads the session through `SessionRepositoryAdapter.findById` — a `@Transactional` persistence adapter. The controller *then* returns an `SseEmitter`, which starts Spring MVC async processing.

`spring.jpa.open-in-view` was at Spring's default `true`. That binds an `EntityManager` to the request thread at request start via `OpenEntityManagerInViewInterceptor`, and does not close it until the request *completes*. An SSE request does not complete until the stream ends — bounded only by `spring.mvc.async.request-timeout: 600000` (ten minutes, per ADR-034). Because the token check opened a transaction, that bound `EntityManager` has acquired a physical JDBC connection, and it is held for the life of the stream.

The Hikari pool has no explicit configuration, so `maximum-pool-size` is Hikari's default **10** and `connection-timeout` is 30 s. A three-player game opens six streams (three browser contexts × two screens, lobby then game), plus streams belonging to torn-down contexts that stay registered until a heartbeat write discovers the peer has gone. That exhausts ten. Every subsequent DB-touching request then parks in `HikariPool.getConnection`.

The 15-second grid is the SSE heartbeat: `RealtimeProperties.heartbeatInterval` defaults to `15s` and `SseSessionEventPublisher` schedules `beat()` with `scheduleAtFixedRate`, so it is phase-locked. A heartbeat write to a departed peer fails, the stream is closed, its request completes, and its connection returns to the pool — releasing the waiters in a batch.

## Evidence

The defect was diagnosed through a chain of measurements:

1. **A prober run alongside the suite** hit two endpoints repeatedly. `/health` (no database) — 228 samples, slowest **0.058 s**, never stalled. `/api/v1/cards` (belongs to *no session*, but needs a JDBC connection) — 228 samples, slowest **25.218 s**, stalling on the same 15-second grid. This proved the blocking is **not** per-session and that the database is the differentiator.

2. **Thread dumps captured during a stall** showed 3–5 `http-nio-…-exec` threads parked in `HikariPool.getConnection` via `ConcurrentBag.borrow`. The waiters in one dump were `TrickController.getTrickState`, `CardController.listCards`, `TrickController.getOwnHand`, `SessionController.getSessionState` and `SessionStreamController.streamSessionEvents` — so a *new* subscription was blocked too.

3. **`pg_stat_activity` during a stall**: 10 backends, every one `state=idle`, `wait_event=ClientRead`, last statement `COMMIT`, no open transaction. The connections were checked out and held by nothing running.

4. **Leak detection** with `spring.datasource.hikari.leak-detection-threshold: 4000` produced exactly **three** `Apparent connection leak detected` warnings, each with the identical stack tracing through `SessionStreamController.streamSessionEvents` → `ResolvePlayerUseCase.execute` → `SessionRepositoryAdapter.findById`.

5. **The fix validation**: with `spring.jpa.open-in-view=false` the leak warnings vanished, and three consecutive clean-stack E2E runs completed at **15 of 15** (59.7 s, 59.0 s, 58.7 s), against 3 failed / 12 passed in 3.6 minutes before the fix. Across roughly 16,200 non-`/events` requests measured at the proxy, **zero** exceeded one second and the slowest was 0.243 s.

## Decision

Two properties in `src/main/resources/application.yml`:

1. **`spring.jpa.open-in-view: false`** — the root-cause fix.

2. **`spring.datasource.hikari.leak-detection-threshold: 10000`** — the observability half. Ten seconds is far above any legitimate hold, since every persistence adapter carries its own `@Transactional` and performs a single query. This is a diagnostic and not a guard — Hikari logs the stack and moves on; it neither reclaims the connection nor fails the request. The defect ran for three suite runs producing 15-second stalls with zero WARN and zero ERROR, and a forgotten connection was indistinguishable from an idle one.

### Why this is safe, not merely expedient

This codebase never renders a view from a JPA entity, because `clean-architecture.md` mandates that cross-boundary data uses DTOs and not entities. No lazy association is ever traversed after the use case returns. The corollary for future maintainers: a `LazyInitializationException` appearing later must be fixed by fetching what the use case needs, never by setting this back to `true`.

### Regression test

`SessionStreamIntegrationTest.shouldNotHoldAJdbcConnectionForTheLifetimeOfAStream` opens three streams against a real server on a random port, waits for each `:subscribed` frame, then asserts `HikariPoolMXBean.getActiveConnections()` reaches zero. Falsified: with `open-in-view` restored to `true` it fails with `[JDBC connections still checked out while 3 streams are open]`.

## Alternatives considered and rejected

- **Raise `maximum-pool-size`.** Treats the symptom. The leak is unbounded — it scales with concurrent streams, and `SseSessionEventPublisher` caps subscribers at 500 (ADR-034), well above 10. Any pool size can be exhausted, and raising it would mask the next leak.

- **Make the token check non-transactional, or read the session without JPA.** Narrower, but it only removes *this* path's connection acquisition. Any future read on an SSE-serving controller would reintroduce the same leak, and the general property — that an async request holds its `EntityManager` for the stream's lifetime — would remain true and undocumented.

- **Shorten the heartbeat interval.** Would reduce the stall duration without removing the leak, and would increase write traffic to every subscriber. It also mistakes the release mechanism for the cause.

- **Set `spring.mvc.async.request-timeout` lower.** Same category: bounds the damage, does not fix it, and fights ADR-034's deliberate ten-minute stream lifetime cap.

## Consequences

**Positive:**

- The connection pool is no longer exhausted by concurrent SSE streams. The fix is structural — it removes the acquisition path, not merely raises the ceiling.
- Leak detection now surfaces forgotten connections, so the next leak will be caught in development rather than in production.
- No Java production code changed. The fix is two YAML properties.

**Negative — stated plainly:**

- **`spring.jpa.open-in-view: false` is a global setting.** It applies to every request, not only SSE streams. If a future use case legitimately needs lazy loading outside a transaction, the fix is to refactor the use case to fetch eagerly, not to revert this setting.
- **The leak-detection threshold is a diagnostic, not a protection.** It logs and continues. A truly stuck connection still holds its JDBC handle until the request completes.

## Cross-reference notes

- **ADR-034** (SSE subscriber cap, emitter timeout, and heartbeat stall fix): The 15-second release grid described here is the same heartbeat mechanism ADR-034 configures. This ADR fixes the *cause* of the stalls that ADR-034's Decision 4 amendment addressed for the *write* path. No amendment needed.

- **ADR-014** (SSE doorbell transport): The transport design is unaffected. This ADR addresses connection management, not the transport itself. No amendment needed.

- **ADR-069** (SSE doorbell catch-up read): That ADR's Consequences section disclosed this defect as separately tracked and unfixed. This ADR resolves it, and ADR-069 carries a dated amendment of 2026-09-06 pointing here. That amendment also corrects two things its original disclosure got wrong: the blocking was not confined to "a single game session" — a request belonging to no session stalled for 25 seconds in the same window — and the heartbeat was the release mechanism rather than the cause.

- **ADR-008** (Liquibase): Not directly related. No amendment needed.

- **ADR-012** (deployment target): The fix goes in `application.yml`, the default profile, so it applies everywhere including `prod`. This is correct — the connection pool is a runtime concern, not an environment-specific one, and the leak would occur identically in any environment where SSE streams are opened. The two-profile design (ADR-012, ADR-049) means this setting is identical everywhere, which is the correct outcome for a pool-level fix.

## Related

- [ADR-034](ADR-034-sse-subscriber-cap-and-emitter-timeout.md) — SSE emitter timeout and heartbeat
- [ADR-014](ADR-014-realtime-transport.md) — SSE transport design
- [ADR-069](ADR-069-sse-doorbell-catch-up-read.md) — catch-up read fix, whose Consequences section references this defect
- [ADR-012](ADR-012-deployment-target.md) — two profiles, identical configuration
- `src/main/resources/application.yml` — the fix location
- `src/test/java/org/maglez/eop/adapter/web/SessionStreamIntegrationTest.java` — regression test
- EOP-227 — this story