# ADR-019: Session Lifecycle, Join Codes, and How the Stream Is Authenticated

**Status:** Accepted (next-player formula amended 2026-08-12 — see ADR-023)
**Date:** 2026-08-05
**Deciders:** @tech-lead, @security-auditor

## Context

EOP-10 builds the first externally reachable write path in this application.
Everything shipped so far is read-only: a card catalogue and a health endpoint.
From this story onward, an anonymous caller can create rows.

The workflow it has to support is fixed by the PRD (§3.1, §3.2). A facilitator
enters a display name, gets a short code and a shareable link, and pastes that link
into whatever chat window the design review is happening in. Players open the link,
enter a display name, and wait in a lobby watching each other arrive. When there
are enough of them, the facilitator starts play.

Three prior decisions constrain the design and leave one question explicitly open.
ADR-014 chose SSE and established that **reconnection is a re-read, never a
replay**, because no event history is kept. ADR-015 chose an opaque token in a
custom request header, and left open how that header reaches a streaming endpoint
given that the browser's `EventSource` API cannot set headers — recording that
"the answer must not be 'query parameter' by default." ADR-013 chose feature flags
as Spring configuration properties.

The game also has a hard floor of three players. It is a rule from the source
whitepaper, not a tunable: with two players there is no third perspective to
challenge a threat, which is the entire mechanic. Six is the ceiling from the PRD.

## Decision

### Five endpoints, and the state endpoint is the only one that reports state

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/sessions` | create; returns session id, join code, identity token |
| `POST` | `/api/v1/sessions/{joinCode}/players` | join by code |
| `GET` | `/api/v1/sessions/{sessionId}` | read state — **the reconnect path** |
| `GET` | `/api/v1/sessions/{sessionId}/events` | stream change notifications |
| `POST` | `/api/v1/sessions/{sessionId}/start` | facilitator closes the lobby |

`start` is in this story rather than deferred to EOP-14, even though dealing the
deck is not. Without it, `IN_PROGRESS` is unreachable through the API, and the
requirement that joining an in-progress game returns 409 could only be tested by
fabricating a database fixture — a test that proves the handler works against a
state the application cannot actually reach. Starting a session establishes that
the lobby is closed. Nothing else.

### Join codes: six Crockford base32 characters, and the limiter is therefore a primary control

Generated with `SecureRandom` over the Crockford base32 alphabet
`0123456789ABCDEFGHJKMNPQRSTVWXYZ` — the digits plus the twenty-two letters left
after removing `I`, `L`, `O` and `U`. `I`/`L` and `O` are excluded because they are
misread as `1` and `0` on a video call, which is the actual input channel; `U` is
excluded because it turns random strings into words nobody wants to read aloud in a
meeting. Input is normalised before lookup — upper-cased, with `I` and `L` mapped
to `1` and `O` to `0` — so a human transcription error is not a failed join. Output
is always the canonical upper-case form.

Six characters is 32⁶, about 1.07 × 10⁹ codes, or roughly **thirty bits of
entropy**. Eight characters was the recommendation. Six was chosen deliberately for
usability: this code is read aloud and typed by hand.

**The consequence is stated plainly rather than buried: at thirty bits the rate
limiter is a primary security control, not defence in depth.** Thirty bits is
unguessable only while guessing is slow. An attacker permitted unlimited attempts
enumerates the keyspace in a time that is measured, not theoretical. This is why
the limiter is in the same story rather than a follow-up, and why removing it later
is a security regression rather than a simplification.

Collisions are handled by inserting against a unique constraint on `join_code` and
retrying on violation, with a bounded number of attempts before failing. The
database, not a pre-insert `SELECT`, decides whether a code is taken — a check-then-
insert has a race window and this endpoint is concurrent by nature.

> **Amended 2026-08-14 — what "before failing" answers with (EOP-17).**
> This section, and the seat-order section below, each specify a bounded retry and
> then say only that the attempt "fails". Neither named a status, so both budgets
> exhausted into `handleUnexpected` and answered **500 with a stack trace** until
> EOP-17. The statuses are recorded here, next to the retry loops that produce them,
> for the same reason the 429 below is recorded inline rather than left to the
> handler: a status is part of the design of a bounded retry, not an implementation
> detail of it.
>
> - An exhausted **join-code** budget answers **503 with `Retry-After: 5`**. It is a
>   capacity statement, not a fault: the code space is finite and thirty bits wide,
>   the same request is expected to succeed shortly, and nothing malfunctioned. A
>   caller cannot provoke it, so each occurrence is genuine capacity evidence and is
>   logged at `WARN` with its trace.
> - An exhausted **seat** budget answers **409**, beside `SessionFullException`. Both
>   mean "the lobby filled underneath you"; they differ only in whether the domain
>   saw it before the write or the unique constraint said so during it, and that
>   distinction is deliberately invisible to a client whose next move is identical
>   either way. A caller holding a valid join code *can* provoke it, so it is logged
>   at `DEBUG` without a trace — the trace would be the flood.
>
> The asymmetry in log levels is therefore derived from provokability, not from
> severity. Five seconds is a guess; it only has to be a defensible one, since there
> is no queue draining at a known rate to compute it from.

### Join attempts are rate-limited in memory, per IP and per code

A sliding window over failed attempts, keyed both by client address and by the code
being tried, returning 429 with `Retry-After`. The clock is injected so the window
is testable without sleeping. Per-code as well as per-IP because an attacker
distributed across addresses is still enumerating one keyspace, and because a
single code under sustained attack is a signal in itself.

**The counters live in process memory and reset when the container restarts.**
Accepted: a restart is a deployment, performed by an operator, and an attacker
cannot trigger one. A distributed limiter, or one backed by the database, would add
a write path and a table to protect something that a single instance behind a single
reverse proxy does not need (ADR-012, ADR-017).

**Tracked-key cap and fail-closed saturation (EOP-18, 2026-08-15).** Each map is
bounded at `MAX_TRACKED_KEYS = 10 000` entries. When a map is full and a new key
arrives, `checkAllowed` first attempts to evict windows whose entries have all aged
out of the sliding window; if the map is still full after eviction, it throws
`TooManyJoinAttemptsException` immediately — before any database work. This is
fail-closed: an untracked address is refused, not silently admitted. `recordFailure`
silently drops the record for a saturated map — the key stays untracked while the
table remains saturated, so this caller's subsequent attempts are refused by
`checkAllowed`'s saturation check — so the map stays at or near the cap. This is a soft cap: concurrent
new-key requests can transiently overshoot `MAX_TRACKED_KEYS` by the number of
in-flight threads before any of them inserts; the overshoot is bounded by the Tomcat
thread-pool size (~200) and is not a security concern.

**Both windows are always evaluated (EOP-18).** `recordFailure` records in both the
address window and the code window regardless of which one is saturated. Silencing the
per-code counter under address-table saturation would disable the control that defends
against a distributed enumeration attack — the exact scenario this ADR identifies as
the primary threat. The two windows are independent and neither suppresses the other.

**Atomic check-and-record (EOP-18).** `recordFailure` acquires `synchronized(window)`
on the deque before pruning, checking the allowance, and appending the new timestamp.
This is the only site that mutates a window deque. `checkAllowed` is a best-effort
pre-check that takes no lock on the window deques; it may evict aged-out map entries
via `evictEmptyWindows` (a map mutation, not a deque mutation). It avoids database
work for clearly over-limit callers; the authoritative gate is `recordFailure`. Two concurrent threads
racing at the limit boundary cannot both pass: the second thread to acquire the monitor
sees the count already at the limit and is refused.

**`recordFailure` throws `TooManyJoinAttemptsException` when a window is exhausted
(EOP-18).** The port declares `@throws TooManyJoinAttemptsException` on
`recordFailure`; the implementation throws after evaluating both windows. This is the
authoritative atomic gate for concurrent callers that both passed `checkAllowed`.
Saturation (table full, new key) is handled silently — the record is dropped and no
exception is thrown. The key stays untracked while the table remains saturated, so
this caller's subsequent attempts are refused by `checkAllowed`'s saturation check;
`recordFailure` need not duplicate that refusal. Window
exhaustion (count at limit after the atomic prune-check-add) does throw, and this
supersedes any domain exception the use case was about to raise: a throttled caller
receives 429 regardless of whether the session was full, not joinable, or the code was
unknown. This is intentional — 429 leaks strictly less information than 404 vs 409,
which serves the anti-oracle intent of the limiter.

### Seat order is assigned once, at join, and enforced by the database

Play is clockwise. "Who plays next" is derived from the current leader's seat plus
the number of cards already in the trick, so `seatOrder` is load-bearing domain
data rather than presentation ordering.

> **Amended 2026-08-12 — see [ADR-023](ADR-023-deal-remainder-and-turn-order.md).**
> The formula in the paragraph above holds only while every seat still holds a card.
> EOP-14 deals the whole 74-card deck with the extra cards going to the lowest seats,
> so at four and five players hands are unequal and the **final trick of a session is
> short** — fewer cards than there are players. The general form is **the next seat
> clockwise that still holds a card**. Copying the simpler formula into play-order code
> will produce a defect that appears only on the last trick, only at four and five
> players. The decision recorded in this section — that `seatOrder` is assigned once at
> join, never re-derived, and enforced by a unique constraint — is unchanged.

It is assigned at the moment of joining and **never re-derived** — not from a
database sort, not from `joined_at`, not from list position. The failure this
prevents is specific: a player disconnects and reconnects, a read-time derivation
puts them somewhere else, and the table silently rotates mid-game. The facilitator
takes seat 0.

Correctness under concurrent joins is enforced by a unique constraint on
`(game_session_id, seat_order)`, not by application logic. Two players submitting a
join at the same moment cannot both take seat 3; one insert fails and retries. A
`MAX(seat_order) + 1` computed in application code without that constraint is a
race condition that appears only under the exact conditions of a real lobby filling
up.

### The stream takes the token in the header, and there is no query-parameter fallback

**This closes the open question ADR-015 left for this story.**

`GET /api/v1/sessions/{sessionId}/events` requires the same
`X-EoP-Player-Token` header as every other authenticated request. It does not
accept the token as a query parameter, and no code path exists that would read one.

A query parameter was rejected for the reason ADR-015 anticipated: it writes a
bearer credential into the reverse proxy access log, the browser history, and the
address bar of a screen being shared during the very meeting this game is played
in. Since the token *is* the entire control (ADR-015), logging it is not a minor
hygiene issue.

The cost is real and is accepted: the browser's `EventSource` cannot set headers,
so the client must consume the stream with `fetch` and read the response body
incrementally. That is more client code than `new EventSource(url)`. It lands in
EOP-11. Until then the stream is verified with `curl -N -H`.

A short-lived single-use stream ticket — exchange the token for a URL-safe ticket
valid for one connection — was also considered and rejected as the wrong shape for
this project: it is a second credential type with its own issuance, expiry and
storage, added to avoid writing fifteen lines of `fetch` parsing.

### Errors

RFC 9457 problem details throughout, via the existing `GlobalExceptionHandler`
(ADR-005). Domain exceptions carry no HTTP vocabulary; the mapping lives in the web
adapter.

**404 on an unknown join code is byte-for-byte identical** whether the code never
existed, was mistyped, or belonged to an abandoned session. Distinguishing them
turns the endpoint into an oracle that confirms which codes are real, which is
worth more to an attacker than any of the three messages is worth to a user.

The `sessionId` endpoints deliberately do **not** hide existence in the same way. A
session id is an unguessable UUID (ADR-018), so there is nothing to conceal, and
the asymmetry is intentional rather than an inconsistency.

**403, not 401, for a missing or unrecognised token.** There is no authentication
scheme here — no realm, no challenge, nothing the client could retry differently. A
401 must carry `WWW-Authenticate`, and emitting one would advertise a challenge
that does not exist. The request was understood and refused, which is what 403
means. The same 403 covers a participant attempting to start a session.

**409 for three distinct conditions**, separated by the problem detail `title`:
joining a session that has left `LOBBY`, joining a table already holding six, and
starting with fewer than three players.

### Behind a feature flag, and the flag-off behaviour is the absence of code

`eop.features.session-lifecycle`, on by default as of EOP-25 (2026-08-16) — previously false by default (ADR-013). The controller is
annotated `@ConditionalOnProperty`, so with the flag off the bean does not exist,
no handler is mapped, and Spring's own no-handler response — already rendered as a
problem detail — returns 404 for every path. Flag-off behaviour is not a branch
that could be wrong; it is the absence of a bean.

Worth noting because it looks like a contradiction: ADR-013 states that flagging
starts with EOP-7. EOP-7 was the first live deployment story and has since been
**closed as superseded** — the owner withdrew cloud deployment, and EOP-16 satisfies
the deployment goal instead — so **EOP-10 introduces the first flag in the
codebase.** The ADRs do not disagree; the story that was supposed to go first no
longer exists. ADR-013 has been corrected to point here.

**Amendment, 2026-08-05 (during EOP-10 implementation).** ADR-013 describes a flag as
being read through a `@ConfigurationProperties` class with `@Validated` and gated with
`@ConditionalOnProperty`. For this flag, only the second half applies, and there is no
`EopFeatures` class. `@ConditionalOnProperty` is evaluated against the `Environment`
while bean definitions are being decided, which is before any `@ConfigurationProperties`
bean has been bound, so a boolean field mirroring this flag would have no reader: it
would be a typed, validated, dead property that a future maintainer could change with no
effect. The property is instead declared explicitly as `false` in `application.yml`, where
its comment documents it, and `matchIfMissing` is left at its default so an absent property
fails closed. The typed-and-validated idiom ADR-013 asks for still appears in this story,
for the value it suits — `eop.realtime.heartbeat-interval`, which is read at runtime rather
than used to decide whether a bean exists.

## Consequences

**Positive:** the reconnect path and the first-load path are the same request, so
recovery is exercised by every page load rather than only when something goes
wrong. This is the property ADR-014 was aiming at, made concrete.

**Positive:** seat order is protected by a constraint rather than by care, so the
one class of bug that would corrupt an entire game — silently rotating the table —
cannot be introduced by a later refactor of the join logic.

**Positive:** the flag makes this shippable before the UI exists, so the endpoints
can be exercised against a running server — with `curl` today and by EOP-11's
client tomorrow — without a half-finished lobby being reachable by default.

**Negative — thirty bits of entropy makes the limiter load-bearing.** Stated in the
decision and repeated here because it is the single most important thing to
carry forward: the join code is short enough that its security depends on the rate
limiter working. Eight characters would have made the limiter a courtesy. Six makes
it a control. Anyone tempted to remove or weaken it must lengthen the code first.

**Negative — the limiter forgets everything on restart.** A deploy resets every
counter. Not attacker-triggerable, and therefore accepted, but it means the
protection is weakest immediately after a deployment.

**Negative — fail-closed saturation can affect legitimate users (EOP-18).** When the
address map holds 10 000 distinct keys, a new legitimate user whose address has never
been seen before receives 429 rather than being admitted. The window self-heals within
one minute as entries age out, and reaching 10 000 distinct keys requires a sustained
botnet. Accepted: the alternative (fail-open) silently disables the primary control
under the exact attack this ADR is designed to resist.

**Negative — bounded eviction race under saturation (EOP-18).** When the tracked-key
table is at capacity, `checkAllowed` calls `evictEmptyWindows` to reclaim aged-out
entries. A thread that has just obtained a fresh empty deque via `computeIfAbsent` but
has not yet entered `synchronized(window)` can have that deque removed by a concurrent
`evictEmptyWindows` sweep (the `allMatch` predicate is vacuously true for an empty
deque). The thread then appends its failure to an orphaned deque no longer reachable
from the map; the next request for the same key creates a fresh deque and the counter
resets. This can cause one failure to be silently lost per race. The race is reachable
only when the map is at `MAX_TRACKED_KEYS` and is bounded to a single undercount per
event; the lost record is a single undercount for a caller that was admitted (eviction
freed space, so `checkAllowed` passed). This permits at most one extra failed attempt
per race and cannot be amplified beyond that. Accepted as a negligible residual risk
under an adversarial saturation scenario.

**Negative — the client cannot use `EventSource`.** More client code, and a
`fetch`-based reader must handle partial frames arriving across chunk boundaries,
which is a class of bug `EventSource` does not have. The alternative was a
credential in the access log.

**Negative — `connectionStatus` is advisory and will sometimes lie.** The emitter
registry is a broadcast list, not a presence list (ADR-014): a dead client is only
discovered on the next write. So the field can report `CONNECTED` for a player who
has gone away. It is a display hint and must never be an input to a game rule.

**Neutral — six is the ceiling and three the floor, both in the domain.** Neither
is configurable, because both come from the game rather than from operations. A
seventh join is a 409, not a resizing decision.

**Neutral — no session expiry in this story.** Rows accumulate. At the volume this
application will see, a cleanup job is speculative work; `ABANDONED` exists in the
status enum so that the concept has somewhere to live when it is needed.

> **Amended 2026-08-16 (EOP-22).** This consequence is reversed by
> [ADR-036](ADR-036-session-expiry-and-sweep.md). EOP-22 introduces a 24-hour TTL
> (`expires_at` column, Liquibase changeset `006`), an expiry guard in
> `ResolvePlayerUseCase`, and a scheduled sweep (`SweepExpiredSessionsUseCase` +
> `ExpiredSessionSweepScheduler`) gated on `eop.features.session-lifecycle`. The
> rationale for the reversal — credential lifetime rather than volume — is recorded
> in ADR-036.

## Related

- [ADR-014](ADR-014-realtime-transport.md) — SSE, and why reconnection is a re-read rather than a replay
- [ADR-015](ADR-015-player-identity.md) — the opaque token; this ADR closes the streaming-header question it left open
- [ADR-018](ADR-018-uuid-v7-identifiers.md) — identifiers for `game_session` and `player`; join codes and tokens are deliberately not UUIDs
- [ADR-013](ADR-013-feature-flags.md) — the flag mechanism; EOP-10 rather than EOP-7 introduces the first flag
- [ADR-005](ADR-005-error-handling-strategy.md) — RFC 9457 problem details and where the HTTP mapping lives
- [ADR-008](ADR-008-database-migration-liquibase.md) — the migration precedes the entities
- [ADR-020](ADR-020-session-concurrency-control.md) — how the seat constraint and the status guard actually serialise concurrent joins, and why `@Version` is not the mechanism
- [ADR-012](ADR-012-deployment-target.md) — one process, no TLS, restart on deploy; the EC2 target is withdrawn but every premise this ADR borrows from it still holds
- [ADR-016](ADR-016-local-container-runtime.md) — the local container stack this actually runs in, and the restart that resets the limiter
- [ADR-021](ADR-021-trusted-proxy-forwarded-for.md) — restores the primary control this ADR depends on. The decision here is unchanged and nothing in it is withdrawn; ADR-021 records that the address the limiter keys on was caller-supplied until EOP-26, so the throttle that makes thirty bits acceptable could be bypassed by rotating one header
- [ADR-033](ADR-033-session-creation-rate-limit-and-body-size-cap.md) — the companion creation-rate limiter and body-size cap introduced by EOP-19; counts successes rather than failures, one window not two, reserve-before-work pattern
- [Runtime view](../architecture/runtime-view.md) — the reconnect, subscribe and create/join/start sequences
- [C4 container diagram](../architecture/C4-Diagrams.md) — where the controller, the publisher and the limiter sit
- [PRD §3, §4, §5](../requirements/PRD-eop-card-game.md) — the workflow, the player range, and the domain model
- EOP-8 (spike), EOP-10 (this story), EOP-11 (the `fetch`-based client), EOP-14 (dealing), EOP-18 (harden limiter: fail-closed saturation, atomic check-and-record), EOP-19 (session creation limiter and body-size cap — see ADR-033)
