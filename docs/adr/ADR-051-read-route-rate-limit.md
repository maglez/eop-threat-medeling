# ADR-051: Read-Route Rate Limiting at the MVC Interceptor Layer

**Status:** Accepted  
**Date:** 2026-08-23  
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor  
**Story:** EOP-88

---

## Context

Before this decision the application rate-limited two write paths and
nothing else. `InMemorySessionCreationLimiter` capped session creation per
client address (ADR-033) and `InMemoryJoinAttemptLimiter` capped join-code
guessing (ADR-019). Every read route was uncapped: a caller could issue
`GET` requests at whatever rate the network allowed.

That gap was raised during the EOP-82 review — MEDIUM by
@security-auditor, MINOR by @architecture-guardian — because EOP-82 made
the most expensive read in the API reachable and gave the browser a button
that drives it. `GET /api/v1/sessions/{id}/leaderboard` resolves the
player, loads the session, loads the recorded result, loads the whole
trick history (up to 66 plays in a three-player game) and recomputes a
`ScoreSheet` from scratch on every call, because the score is derived from
play rather than accumulated (ADR-030). `GameOverScreen` renders a
`Retry loading results` button whenever that read fails, and EOP-82 gave
it a pending state but no attempt cap, so a human could hold it down.

Three framing corrections matter for reading the rest of this ADR.

**There is no authentication.** EOP-88 was filed against "authenticated
read routes", but ADR-015 records that this application has no
authentication and no accounts. What the ticket means is routes that
require the `X-EoP-Player-Token` bearer capability, which returns 403 when
absent. This ADR says *token-bearing* where the ticket said
authenticated.

**The gap was never an unauthenticated amplifier.** The leaderboard route
demands a seated player's token, so the pre-existing exposure was smaller
than the ticket's title suggests. It was still a real gap, because a
single legitimate participant — or anyone who has watched one join code
being read aloud — could saturate the process.

**The absent control was cross-cutting, so the fix had to be.** Adding a
limiter call to `GameOverController` would have closed the one route
named in the ticket and left five others open, and would have left the
next `GET` route to be added open by default.

---

## Decision

### 1. The limiter runs as a Spring MVC `HandlerInterceptor`

`ReadRateLimitInterceptor` implements `HandlerInterceptor.preHandle` and
throws `RateLimitedException` when a caller has spent its allowance.
`WebInterceptorConfiguration` registers it — the first `WebMvcConfigurer`
in the application.

**Why not a servlet filter.** A filter runs outside the
`DispatcherServlet`, so an exception thrown there never reaches
`@ControllerAdvice`. We would have had to serialise the RFC 9457 problem
document by hand inside the filter, duplicating `GlobalExceptionHandler`
and breaking the single-advice rule in `.opencode/rules/error-handling.md`.
That is a real cost for no benefit: nothing about this limiter needs to
run before request mapping. An interceptor runs inside the dispatcher, so
the throw is rendered as `429 application/problem+json` with a
`Retry-After` header by code that already exists and is already tested.

**Why not Caddy.** Caddy's rate limiter is a third-party plugin that is
not compiled into the image `ui/Dockerfile` builds, so adopting it means
maintaining a custom Caddy build. It would also protect only traffic that
arrives through Caddy: port 8080 is reachable directly, which ADR-033
already records as an accepted limitation. A limit enforced in the
application holds wherever the application is reached.

**Why not per-controller.** See the third framing correction above.

### 2. The key is the resolved client address, and nothing else

`preHandle` derives its key from `ClientAddressResolver.of(request)` —
the same seam the creation and join limiters use. With
`eop.web.trusted-proxies` empty, which is the shipped default and the test
configuration, the resolver ignores `X-Forwarded-For` entirely and returns
the peer address. `server.forward-headers-strategy: none` in
`application.yml` is a second, independent guard: neither Tomcat nor
Spring rewrites `getRemoteAddr()` from a header.

**No request header can influence the key.** This is the whole point of
EOP-88's third acceptance criterion, and it exists because EOP-26 found
the opposite: the join throttle keyed on a caller-supplied
`X-Forwarded-For`, so a fresh header value per request meant a fresh empty
bucket, and ADR-019 relies on that throttle as a *primary* security
control rather than as defence in depth. ADR-021 fixed the resolution;
this ADR reuses the fixed seam rather than inventing a second one.
`ReadRateLimitInterceptorTest` asserts the property directly over twelve
forwarding and identity headers.

**Why not the player token.** Keying on the resolved player would give
NAT'd households independent allowances, which is nicer for legitimate
users. It is unsafe here for a specific reason: the counter fails closed
when its key table saturates (ADR-033), and an attacker can mint an
unlimited number of distinct bogus tokens. A token-derived key would
therefore let a single caller fill the table with junk keys and have the
limiter refuse *everyone*. A spoofable identifier must not be allowed to
select the bucket a fail-closed control counts in.

### 3. Scope is `GET` and `HEAD` under `/api/v1/**`, with SSE excluded

The interceptor is registered on `/api/v1/**` and returns `true`
immediately for any method that is not `GET` or `HEAD`. `HEAD` counts
because it reaches the same handler and costs the same work. Write routes
keep their existing dedicated limiters; nothing about their behaviour
changes.

A pattern rather than a list of five paths is deliberate. A hand-listed
set of routes is the opposite of a cross-cutting control: it is correct
only until someone adds a route. This also brings the *public* card
catalogue under the limit, which is more exposed than the token-bearing
routes rather than less.

`/api/v1/sessions/*/events` is excluded. Spring runs `preHandle` again on
the `ASYNC` dispatch of an async request, so a naive counter would charge
every SSE stream at least twice, and a long-lived stream is not a request
rate in any case. Its cost is already bounded by the per-session and
global subscriber caps and the ten-minute emitter timeout (ADR-034).
**SSE reconnection is therefore not rate-limited by this decision**, and
that is a real remaining gap rather than something this ADR closes; see
the accepted limitations.

### 4. Configuration under `eop.web.read-rate-limit`

`ReadRateLimitProperties` is a `@ConfigurationProperties` `@Validated`
record with `limit` (default 300, `@Min(1)`) and `maxTrackedKeys`
(default 10 000, `@Min(1)`). It is a separate record from
`TrustedProxyProperties` rather than a third field on it, because that
record's name already stretches to cover `sessionCreationLimit` and a
third unrelated limit would make it actively misleading. Both bind
sibling prefixes under `eop.web`.

**Why 300.** The SSE doorbell (ADR-014) means every client re-fetches
state on each event rather than receiving state in the event. A
three-player game generates roughly 66 trick events, each waking about two
re-fetches per client, so a household behind one NAT peaks near 100 reads
a minute. 300 leaves roughly threefold headroom while still bounding the
retry button by two orders of magnitude below what a held key could
achieve.

### 5. `RateLimitedException` is reused; no new exception, no new handler

`GlobalExceptionHandler` already maps `RateLimitedException` to
`429 application/problem+json` with `Retry-After`, and its Javadoc already
records that the type is deliberately neutral so that any limiter can
throw it. `GlobalExceptionHandlerTest` already covers that mapping, so
EOP-88's second acceptance criterion was half-satisfied before the story
started; `ReadRateLimitIntegrationTest` adds the body assertion.

### 6. A fresh `SlidingWindowCounter`, with the duplication accepted

The counting mechanism is extracted as a package-private
`SlidingWindowCounter` in `org.maglez.eop.adapter.web`: a
`ConcurrentHashMap` of per-key deques, pruned and checked and appended
under one lock per key so two threads at `limit - 1` cannot both pass, and
refusing new keys once the table is full.

`InMemoryJoinAttemptLimiter` and `InMemorySessionCreationLimiter` were
**not** migrated onto it in this story, so the same algorithm now exists
in three places. That is a deliberate trade, not an oversight. ADR-019
designates the join throttle a primary security control, and refactoring
it in the same change that introduces a new limiter would have put a
security-critical control and an untested new one in one review. The
migration is filed as a follow-up.

### 7. No use-case port

The two existing limiters have ports in `org.maglez.eop.usecase` because
use cases call them. This limiter is called by an interceptor, which is
already an interface adapter, so it lives entirely in
`org.maglez.eop.adapter.web` and depends inward only on
`RateLimitedException`. A port here would be an inward-facing abstraction
with no inward caller.

### 8. The retry button is capped and backed off, client side

`GameOverScreen` now allows five retries, with a doubling cooldown of 1,
2, 4, 8 and 16 seconds — 31 seconds of enforced waiting across the five —
after which it replaces the button with guidance to reload the page. Five
attempts is ample for the EOP-86 race the button exists to survive (a
game-completed event arriving before the result row is committed) and far
below the server limit.

This is defence in depth and nothing more. The control is the server-side
limit in decision 1; the client half only bounds the one amplifier the
ticket named, and a caller who is not using our front end is unaffected
by it.

---

## Consequences

The named gap is closed: `GET /leaderboard` and every other read under
`/api/v1/**` now has a documented, configurable per-address limit that
returns a problem document. The limit is enforced wherever the
application is reached rather than only through Caddy. A future `GET`
route is covered the moment it is mapped, with no further work.

Test isolation does not depend on forging the limiter key. The suite-wide
limit is raised in `src/test/resources/application.properties` and the
refusal tests lower it for their own context with
`@SpringBootTest(properties = …)` plus `@DirtiesContext`. Sending a
different `X-Forwarded-For` per test would be forging the key, which is
the EOP-26 vulnerability rather than a test technique.

### Accepted limitations

1. **The counters are in memory and are forgotten on restart.** A
   restart is a deployment, and a deployment is not something an attacker
   can trigger.
2. **The limit is per instance.** A shared store would be the right
   answer for more than one instance, and there is one instance
   (ADR-012).
3. **Users behind one NAT share a bucket.** This is the standard
   trade-off for per-address limiting, and decision 2 explains why the
   alternative is worse here. The 300 default is sized to absorb it.
4. **Address rotation sidesteps the window.** A caller with many source
   addresses gets many allowances. This is a deterrent, not an absolute
   cap, and the fail-closed key table bounds how far the rotation scales
   before the limiter starts refusing new keys outright.
5. **Saturation refuses new keys, including legitimate ones.** Once
   10 000 keys are tracked, a caller whose key is not already present is
   refused with a one-second `Retry-After` until eviction frees space.
   Admitting untracked requests would let an attacker bypass the limiter
   by exhausting the table first, so failing closed is correct — but the
   cost is real and falls on innocent callers during an attack.
6. **SSE reconnection is not rate-limited.** Decision 3 excludes the
   event stream, so a client that opens and drops streams in a loop is
   bounded only by the subscriber caps in ADR-034, which limit
   concurrency rather than churn. Closing this needs a counter that
   distinguishes the initial dispatch from the `ASYNC` one; it is not
   attempted here.
7. **The public card catalogue shares the limit with token-bearing
   reads.** A caller who spends its allowance paging the catalogue will
   be refused the leaderboard. With a 300 default this is unlikely to
   bite a real user, but the two are not budgeted separately.
8. **The sliding-window algorithm now exists three times.** See decision
   6. Until the follow-up lands, a fix to one implementation must be
   applied by hand to the others.
9. **Nothing enforces that the counters stay consistent with the docs.**
   The 300 default, the sizing arithmetic and this ADR are held together
   by review, not by a test.
10. **A raised suite-wide limit means the shipped default is not what
    the integration suite exercises.** The refusal tests pin their own
    limit, but no test asserts that 300 is the value production runs
    with.

### Story ledger

- EOP-88 (2026-08-23): initial implementation — `SlidingWindowCounter`,
  `ReadRateLimitInterceptor`, `WebInterceptorConfiguration`,
  `ReadRateLimitProperties`, and the `GameOverScreen` attempt cap.

---

## Related

- ADR-012 — Single-instance deployment (why a per-instance limit is enough)
- ADR-014 — Real-time transport via SSE (the doorbell that drives re-fetches)
- ADR-015 — Player identity via a server-issued opaque token (there is no authentication)
- ADR-017 — Single-origin serving via Caddy (why the Caddy layer was considered)
- ADR-019 — Session lifecycle and join codes (the join-attempt limiter as a primary control)
- ADR-021 — Trusted-proxy allow-list for `X-Forwarded-For` (the key-forging vulnerability this reuses the fix for)
- ADR-030 — The score is derived from play, not accumulated (why the leaderboard read is expensive)
- ADR-033 — Session creation rate limit and request body size cap (the mechanism and the fail-closed rationale)
- ADR-034 — SSE subscriber cap and emitter timeout (what bounds the excluded event stream)
- ADR-042 — Enable the game-over feature flag (the retry button this caps)
