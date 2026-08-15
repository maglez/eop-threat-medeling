# ADR-034: SSE Subscriber Cap, Emitter Timeout, and Heartbeat Stall Fix

**Status:** Accepted
**Date:** 2026-08-15
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor
**Story:** EOP-20

---

## Context

`SseSessionEventPublisher` had three independent resource-exhaustion defects that
were invisible to the build and to a functional test suite.

This ADR reverses the `SseEmitter(0L)` no-timeout decision recorded in ADR-014
(§ Realtime Transport, Decision 3). See ADR-014 Amendments.

**Defect 1 — Unlimited emitter lifetime.**
`new SseEmitter(0L)` creates an emitter with no timeout. A client that connects
and never disconnects holds a servlet async request indefinitely. Spring's async
machinery also has no per-request deadline unless `spring.mvc.async.request-timeout`
is set. An unattended load test or a browser tab left open overnight would
eventually exhaust the server's thread pool or accept queue.

**Defect 2 — No subscriber cap.**
`subscribe()` added an emitter to the in-memory registry unconditionally. A caller
could subscribe thousands of times to a single session, or across many sessions,
growing the registry without bound and causing every `beat()` sweep to do O(n)
work where n is attacker-controlled.

**Defect 3 — Single-threaded heartbeat stall.**
`beat()` called `emitter.send()` inline on the single `sse-heartbeat` scheduler
thread. `SseEmitter.send()` acquires an internal lock and writes to the underlying
socket; a slow reader or a subscriber in a network partition blocked every other
subscriber's heartbeat until the socket timed out or the emitter completed. The
number of subscribers was also attacker-controlled (defect 2), compounding the
exposure.

---

## Decisions

### Decision 1 — 10-minute emitter timeout

`new SseEmitter(0L)` is replaced by `new SseEmitter(EMITTER_TIMEOUT_MILLIS)` where
`EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10)`.

Ten minutes is chosen as a balance between reconnect friction and resource liveness.
A well-behaved client receiving heartbeats every few seconds will never hit this
ceiling. An abandoned client is reclaimed at most ten minutes after it disappears.

`spring.mvc.async.request-timeout` is set to `600000` (ms) in `application.yml` so
the servlet container's own deadline matches the emitter's. Without this, the
container can close the async request before the emitter fires its own timeout,
producing a misleading error log and leaving the emitter in a half-open state.

### Decision 2 — Per-session cap of 12

`subscribe()` checks `forSession.size() >= MAX_SUBSCRIBERS_PER_SESSION` before
adding an emitter. If the cap is exceeded, `TooManySubscribersException` is thrown
and mapped to HTTP 429 by `GlobalExceptionHandler`.

The cap is 12: twice `GameSession.MAXIMUM_PLAYERS` (6). The factor-of-two headroom
accommodates reconnect churn — a player refreshing a browser tab opens a new SSE
connection before the old one closes. A factor below 2 would refuse legitimate
reconnects during slow network conditions.

Alternatives considered:
- **Cap equal to MAXIMUM_PLAYERS (6):** Rejected. A simultaneous reconnect by every
  player in a full session would exhaust the cap before any old emitter timed out.
- **Cap of 1 per player:** Rejected. The application has no concept of a player's
  SSE identity; a token-to-emitter mapping would require a new port and schema change
  beyond this story's scope.

### Decision 3 — Global cap of 500

`subscribe()` also checks a global `AtomicInteger totalSubscriberCount`. If the
count is at or above `MAX_TOTAL_SUBSCRIBERS = 500`, `TooManySubscribersException` is
thrown before the emitter is created.

Five hundred allows up to 41 full concurrent sessions (500 / 12 ≈ 41) while
keeping the heartbeat sweep bounded in *emitters* at the global cap. The count is
decremented in `forgetOne()`, which is called on every completion, error, or timeout.

Note: the heartbeat sweep is bounded in *emitters* (by the global cap of 500) but
NOT in *map entries*. `subscribers` and `sessionLocks` are never individually evicted
— entries accumulate for the JVM lifetime, one per distinct session that has ever had
a subscriber. The sweep iterates all map entries every 15 s, including those whose
emitter list is empty. This is a known trade-off of the never-remove invariant that
prevents the lock-identity race (see Consequences).

Two package-private test constructors allow cap overrides without subscribing up to
the production ceilings:
- `SseSessionEventPublisher(RealtimeProperties, int maxTotalSubscribers)` — overrides
  the global cap only, using the production per-session cap of 12.
- `SseSessionEventPublisher(RealtimeProperties, int maxPerSessionSubscribers, int maxTotalSubscribers)` — overrides
  both the per-session and the global cap independently.

### Decision 4 — Bounded send thread pool for the heartbeat

`beat()` now submits each emitter's send as a `Runnable` to a fixed-size
`ExecutorService` named `sse-send-N` (4 threads). The `sse-heartbeat` scheduler
thread enqueues all tasks and returns immediately; it does not block on any send.

Four threads are chosen as a conservative default: at 500 subscribers divided by 4
workers, each worker is responsible for at most 125 sends per sweep, and the sweep
interval is configurable. The pool uses a bounded `ArrayBlockingQueue` of capacity 1000
(approximately two full sweeps of the maximum subscriber count). If all four workers are
blocked on slow sockets, `beat()` submits up to 500 tasks every 15 s; without a bound
the queue grows without limit. The `DiscardOldestPolicy` drops the oldest queued task
when the queue is full — a dropped heartbeat is harmless because the next sweep retries.

A `ThreadFactory` names each thread `sse-send-N` for observability in thread dumps.

Alternatives considered:
- **Virtual threads per emitter:** Considered and deferred. Spring Boot 4.1 enables
  virtual threads but `SseEmitter.send()` holds a `synchronized` lock and is
  therefore pinned. Until Spring releases a non-synchronized SSE path, virtual
  threads are no better than platform threads for this case.
- **Caller-side timeout on the send:** Not sufficient. A socket write may block
  indefinitely at the OS level, and wrapping it in a `Future.get(timeout)` would
  still tie up the calling thread waiting for cancellation.

### Decision 5 — `destroy()` two-phase shutdown

`destroy()` previously called `heartbeats.shutdownNow()` and returned. The
`sse-heartbeat` thread could still be mid-sweep with tasks queued on the send pool.
The corrected sequence is:

1. `heartbeats.shutdownNow()` — stop accepting new beats
2. `heartbeats.awaitTermination(5, TimeUnit.SECONDS)` — wait for the in-flight beat to finish
3. Complete every registered emitter and clear the subscriber map
4. `sendPool.shutdownNow()` — cancel any pending send tasks
5. `sendPool.awaitTermination(5, TimeUnit.SECONDS)` — wait for in-flight sends

`DisposableBean.destroy()` declares `throws Exception`, so the checked
`InterruptedException` from `awaitTermination` propagates without wrapping.

---

## Consequences

- Every SSE connection is bounded to ten minutes rather than infinite.
- The `subscribe()` path can now throw `TooManySubscribersException`, which
  `GlobalExceptionHandler` maps to HTTP 429. Callers that embed SSE subscription
  inside a retry loop should honour the 429 and back off.
- The per-session and global cap checks are made atomic via a `synchronized` block on
  a per-session lock object held in `sessionLocks` (`ConcurrentHashMap<UUID, Object>`)
  combined with a CAS loop on `totalSubscriberCount`, preventing
  overshoot under concurrent load. Without atomicity, a concurrent rush of 10 callers
  could all read the counter below the cap simultaneously and all be admitted,
  overshooting the limit by the degree of concurrency.
- The send pool introduces four additional platform threads per application instance.
  These are idle when no heartbeat is in progress and have negligible memory overhead.
- The global subscriber count requires correct bookkeeping in `forgetOne()`. A missing
  decrement would cause the counter to drift upward and eventually refuse all new
  subscribers. `forgetEveryone()` resets the counter to zero as a defensive measure
  for the test lifecycle and for the graceful shutdown path.
- No schema change. No new port. No flag. The caps and timeout are production-default
  constants; two package-private test constructors allow overriding `maxTotalSubscribers`
  alone or both `maxPerSessionSubscribers` and `maxTotalSubscribers` together.
- Session entries in `subscribers` and `sessionLocks` are never individually evicted —
  they accumulate for the JVM lifetime. The heartbeat sweep iterates all entries every
  15 s, including empty ones. Growth is bounded by the number of distinct sessions ever
  created (rate-limited to 5/address/60s), so the practical ceiling is low, but this is
  a known trade-off of the never-remove invariant that prevents the lock-identity race.
