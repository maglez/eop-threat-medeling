# ADR-033: Session Creation Rate Limit and Request Body Size Cap

**Status:** Accepted  
**Date:** 2026-08-15  
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor  
**Story:** EOP-19

---

## Context

`POST /api/v1/sessions` requires no credential and inserts a permanent row in
`game_session`. Without a limit, an unauthenticated caller can flood the table
at the rate of the network. At 500 req/s that is 43 million rows per day,
consuming every six-character join code and causing legitimate facilitators to
receive opaque 503s once the five-attempt collision retry in `CreateSessionUseCase`
exhausts (`JoinCodeUnavailableException` → 503, ADR-019).

The join-attempt limiter (ADR-019, EOP-18) defends the join-code keyspace
against guessing but does not bound creation: it counts *failures*, and a
creation that succeeds is not a failure. A separate control is needed.

A second concern is request body size. An unauthenticated caller can send an
arbitrarily large body to any `/api/*` endpoint. The application buffers the
whole body before Jackson parses it, so a large body is a memory amplifier.

---

## Decision

### 1. Session creation limiter

A new port `SessionCreationLimiter` (use-case layer, zero framework imports)
exposes three methods:

- `checkAllowed(String clientAddress)` — best-effort pre-check, no lock
- `recordCreation(String clientAddress)` — authoritative atomic gate; **must
  be called before any database work**
- `refundCreation(String clientAddress)` — returns the slot if the downstream
  use case fails after the slot was reserved

`InMemorySessionCreationLimiter` (adapter, `adapter.web`) implements the port
with a `ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>>` sliding
window. The prune, limit check, and insertion are performed under
`synchronized(window)` in `recordCreation`, preventing two concurrent threads
from both passing the check before either records.

`SessionController.createSession()` follows the reserve-before-work pattern:

```
checkAllowed(addr)          // best-effort, no lock
recordCreation(addr)        // atomic gate — before DB write
try {
    createSessionUseCase.execute(...)
    return 201
} catch (RuntimeException) {
    refundCreation(addr)    // return slot on failure
    rethrow
}
```

This ensures that a refused request never commits a row, and that a transient
use-case failure does not permanently consume a creation allowance.

**Why successes are counted, not failures.** A facilitator who creates five
lobbies in a minute is the pattern being limited; there is no "wrong answer"
equivalent to a failed join. Counting failures would allow unlimited successful
creations, which is the attack vector.

**Why one window, not two.** The join limiter uses two windows (per-address and
per-code) because a distributed guessing attack can rotate source addresses
while targeting one code. Session creation has no per-code analogue: a session
does not exist until it is created, so there is no code to walk. The
per-address window alone is sufficient.

**Configuration.** The limit is bound via `TrustedProxyProperties`
(`@ConfigurationProperties(prefix = "eop.web")`), which already owns the
`eop.web` namespace. `sessionCreationLimit` is a new `@Min(1)` field with
default 5. The test suite overrides it to `Integer.MAX_VALUE` in
`application.properties` to prevent exhausting the limiter across the shared
Spring context.

**Fail-closed on saturation.** When the tracked-key table reaches
`DEFAULT_MAX_TRACKED_KEYS = 10 000` entries and a new address arrives,
`checkAllowed` refuses the attempt before any database work rather than
silently admitting it. A flood of distinct keys is itself an attack pattern;
admitting requests that cannot be tracked would let an attacker bypass the
limiter by exhausting the table first.

**Exception type.** The new `RateLimitedException` (use-case layer, no Spring
imports) carries `retryAfter()` and produces a problem detail with title
`"Too many requests"`. This accurately describes a creation throttle.
`TooManyJoinAttemptsException` is unchanged and continues to serve the join
limiter.

### 2. Request body size cap

Caddy enforces `request_body { max_size 16KB }` on all `/api/*` routes,
rejecting oversized bodies at the proxy before they reach the application.

**There is no application-side body-size cap.** `server.tomcat.max-swallow-size`
does not cap request bodies: it limits how many bytes Tomcat will read and
discard from a body it has *already decided to ignore* (e.g., after a 413 from
a filter). For a normal `POST` that a controller handles, Jackson reads the
whole body and `max-swallow-size` never fires. The property was considered
and deliberately not added to `application.yml` — adding it would mislead
future reviewers into believing a second body-size layer exists when it does not.

Direct access to port 8080 (bypassing Caddy) is unprotected. This is an
accepted limitation: the application container publishes no host port in the
deployed topology (ADR-012, ADR-017), and the Compose network is not reachable
from outside the host.

---

## Consequences

### Accepted limitations

1. **In-process memory, forgotten on restart.** The counters are lost when the
   application restarts. A restart is a deployment, not something an attacker
   can trigger. A shared store (Redis) would be the right answer for more than
   one instance; there is one instance (ADR-012).

2. **Per-instance limit.** Horizontal scaling multiplies the effective limit by
   the instance count. There is one instance.

3. **NAT'd users share one bucket.** An entire office behind a single egress IP
   gets 5 creations per minute. This is the standard trade-off for per-IP
   limiting.

4. **Address rotation sidesteps the per-address window.** An attacker rotating
   source addresses (trivial from an IPv6 /64 or a botnet) can exceed the
   per-address limit. The per-address window is a deterrent, not an absolute
   cap.

5. **Saturation defence is O(keys × entries).** `evictEmptyWindows` scans the
   full map on every request once the table is at capacity. This is a known
   amplifier reachable under a key-rotation attack. It is accepted because the
   table size (10 000) is large enough that saturation requires a sustained
   attack, and the eviction scan is bounded by the table size.

6. **Proxy-only body cap.** Direct access to port 8080 bypasses the 16 KB
   limit. The application container publishes no host port in the deployed
   topology.

### Story ledger

- EOP-19 (2026-08-15): initial implementation

---

## Related

- ADR-019 — Session lifecycle and join codes (join-attempt limiter)
- ADR-012 — Single-instance deployment
- ADR-017 — Single-origin serving via Caddy
- ADR-021 — Trusted-proxy allow-list for `X-Forwarded-For`
