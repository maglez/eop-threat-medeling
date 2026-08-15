# ADR-014: Real-Time Transport via Server-Sent Events

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** @tech-lead, @architecture-guardian

## Context

The game is a facilitated multiplayer session (PRD §3.3). When one player plays a
card, every other player must see it without refreshing. The application has no
push mechanism of any kind today, and no websocket dependency on the dependency
list.

The traffic is asymmetric in a way that matters. A player acts a handful of times
per trick — play a card, mark a threat linked, take the trick — and each of those
is a discrete request that wants a status code and a body. Everything else is the
server telling everyone what just happened. Over a 60–90 minute game the
server-to-client direction dominates by an order of magnitude.

Two constraints from earlier decisions bound the choice. The application runs as a
**single process with no load balancer and no session affinity**, so every
deployment is a container restart that drops every connected client. At the time
this ADR was written that followed from ADR-012's single EC2 instance; the EC2
target has since been withdrawn and the process now runs in a local container
(ADR-016), which changes nothing about the constraint — a local container is
restarted at least as often as a remote one. And no reverse proxy had yet been
chosen, so the transport must not quietly impose one.

This decision was made after a time-boxed spike (EOP-8) that ran a real
`SseEmitter` endpoint against the real application. The findings below are
measurements, not expectations.

## Decision

**Server-sent events, using Spring's `SseEmitter`.**

### Why, with the evidence

**It needs no new dependency.** `SseEmitter` ships inside `spring-webmvc` 7.0.8,
already on the classpath via `spring-boot-starter-web`. Websockets would require
adding `spring-boot-starter-websocket`, and a broker-backed STOMP setup would add
more. The spike verified the class is present rather than assuming it. The story
required any new dependency to be justified; the strongest justification is
needing none.

**Latency has an enormous margin.** Two independent subscribers were attached and
three events broadcast. Both received all three, carrying an identical
server-side nanosecond stamp — proving one broadcast fanning out rather than
sequential sends. End-to-end delivery measured at **59 ms against the 500 ms
requirement**, and that figure includes a process spawn from the measuring
harness, so the true number is lower. There is no latency argument for a more
complex transport.

**It does not pin a thread per connection.** `SseEmitter` returns immediately and
completes asynchronously on the servlet container's async path. On one modest host
shared with PostgreSQL, a transport that held a request thread open per
connected player would be a real constraint. At 3–6 players it would not break,
but the property is worth having for free.

**It asks nothing of the reverse proxy that Caddy does not already do.** Caddy
does not buffer proxied responses by default, so SSE works with no directive.
nginx does buffer, and would need `proxy_buffering off` on the event path. This
was a constraint to carry into the reverse-proxy decision rather than a reason to
pick a transport, but it is one fewer thing that can be silently misconfigured.
ADR-017 subsequently chose Caddy, and the `ui/Caddyfile` that resulted contains no
buffering directive on `handle /api/*` because none is needed — the prediction
held.

### Reconnection means re-fetching state, not replaying events

This is the spike's most valuable finding and it changes how EOP-10 and EOP-14
must be built.

The protocol primitives are all there: the spike observed `id:`, `retry:` and
`event:` on the wire, confirmed the reconnect interval is server-controlled, and
confirmed the server does receive a `Last-Event-ID` header when a client
reconnects. The obvious design is therefore to replay the events the client
missed.

**That design is not available here.** After a restart, the spike's event
counter had reset to zero and the subscriber list was empty. A client
reconnecting with `Last-Event-ID: 47` is asking for events the server has no
memory of. Replay would require persisting an event log, which is a second
source of truth alongside the game state it describes.

So: **on reconnect, the client re-fetches the full current game state over a
normal `GET`, and resumes streaming from there.** Events become notifications
that something changed, not the authoritative record of what changed. The game
state in PostgreSQL is the only authority. This is simpler, has one fewer thing
to keep consistent, and is the only option that survives a container restart —
which happens on every deploy.

### A server heartbeat is mandatory

Two findings force this. First, `GET /spike/sse/count` reported two subscribers
when both clients were already dead: an SSE server only discovers a broken
client when it next attempts a write, so **the subscriber list over-reports**.
Any presence or "who is connected" feature must not trust it. Second, when the
server was killed with a subscriber attached, that subscriber did not see a
clean stream end — it hung until its own timeout expired, 30 seconds later, and
then reported a transport error against an HTTP 200.

A periodic comment frame is the single mechanism that fixes both: it fails the
write on a dead subscriber so it can be reaped, and its absence is how a client
notices a dead server promptly rather than waiting on a timeout.

### Rejected alternatives

**Websocket, with or without STOMP and a broker.** The reflex answer, and the
reflex is worth challenging. It adds a dependency, a second protocol with its
own upgrade handshake and framing, more reverse-proxy configuration, and a
bidirectional channel for traffic that is overwhelmingly one-directional. The
client-to-server direction it buys us is already well served by `POST` returning
a status code — and a `POST` gives validation errors an RFC 9457 body (ADR-005),
which a websocket frame does not. Nothing measured in the spike suggests SSE is
insufficient. Websocket becomes the right answer if the game later needs
genuinely high-frequency client-to-server traffic; it does not today.

**Periodic polling.** Simplest of all, and it would work at this scale. Rejected
because it trades a fixed latency floor (half the poll interval, on average) and
a constant stream of mostly-empty requests for a saving SSE does not require —
SSE needs no dependency either. Polling would also make "did anything change"
a database query per client per interval.

## Consequences

**Positive:** no new dependency, so no new convergence risk against the enforcer
plugin and nothing new to keep patched. Latency is a solved problem with a 8×
margin. The transport is plain HTTP, so it debugs with `curl -N` — the spike
did exactly that, which is why the findings are measurements.

**Positive:** because reconnection re-fetches rather than replays, the recovery
path is the same code path as first load. That path gets exercised constantly
rather than only after a failure, which is the opposite of how most recovery
code rots.

**Negative — SSE is one-directional.** Every player action stays a normal
request. This is not a workaround; it is the honest shape of the problem. But it
does mean two mechanisms to reason about rather than one, and an action's effect
arrives back at the acting client twice: once as the response, once as the
broadcast event. Clients must tolerate seeing their own change echoed.

**Negative — the HTTP/1.1 six-connection-per-origin limit applies.** A browser
holds an open SSE connection per tab, against a per-origin cap of roughly six.
Irrelevant at 3–6 players in separate browsers, and irrelevant over HTTP/2 where
streams are multiplexed. It becomes real only if someone opens many tabs on one
machine, which is plausible during local testing. Worth knowing before it is
diagnosed as a server fault.

**Negative — the subscriber list is not a presence list.** Stated above, and
repeated here because it is the kind of thing that gets built on by accident.
Connection count is a lower-bound estimate that decays toward truth only when
writes happen.

**Neutral — no event history.** Deliberate. If the product later needs an audit
trail of a session, that is a persisted domain concern (the PRD notes the
original game's scorekeeper filed one bug per threat), not an event log bolted
onto the transport.

**Neutral — the spike code was deleted.** The throwaway branch
`spike/EOP-8-sse-throwaway` is gone and no production code graduated from it, as
EOP-8 required. The first real emitter arrives with EOP-10.

## Amendments

**2026-08-10 — the deployment premises were corrected; every conclusion stands.**
This ADR reasoned from ADR-012's single `t3.small`. That EC2 target has been
**withdrawn**, EOP-7 is closed as superseded, and the application runs locally in a
container (ADR-016), demonstrated across three browsers on one machine.

**None of the findings or conclusions change, and the two that matter get stronger.**

*Reconnection is still a re-read, never a replay.* The argument never depended on
the host. It depended on there being no persisted event log and on the process
losing its subscriber list when it restarts — both still true, and a locally rebuilt
container restarts more often than a deployed one would have. EOP-10 implemented it
exactly as specified: `GET /api/v1/sessions/{sessionId}` re-reads full state from
PostgreSQL, `Last-Event-ID` is deliberately not honoured, and
`SessionRepositoryAdapter.assemble` takes both its reads from the database so that
no registry or cache can contribute a different answer.

*The heartbeat is still mandatory.* `SseSessionEventPublisher` runs a `sse-heartbeat`
daemon thread at `eop.realtime.heartbeat-interval` and constructs every emitter as
`new SseEmitter(0L)` — no container timeout at all. That combination is only safe
because of the heartbeat: detecting a dead peer is this class's job, and a container
timeout would otherwise close healthy idle lobbies. The over-reporting subscriber
list is unchanged, and EOP-10 respected it: the registry is a broadcast list and is
explicitly not used as a presence list.

*The six-connection-per-origin note became more relevant, not less.* It was written
off as "plausible during local testing". Local testing on one machine is now the
only way this application is demonstrated. It remains harmless — three separate
browsers hold one connection each against a per-origin cap of roughly six, and
Caddy on a single origin (ADR-017) does not change the arithmetic — but anyone
opening several tabs per browser should expect the seventh to stall rather than
diagnose a server fault.

### Amendment — EOP-20 (2026-08-15): Emitter timeout reversed

Decision 3 of this ADR (`new SseEmitter(0L)` — no container timeout) is reversed by ADR-034.
EOP-20 introduces a 10-minute container timeout (`EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10)`)
to close stale connections that have silently dropped. The heartbeat mechanism (Decision 4) keeps
live connections alive within the timeout window. See ADR-034 for full rationale.

## Related

- [ADR-015](ADR-015-player-identity.md) — the other half of this spike
- [ADR-012](ADR-012-deployment-target.md) — one process, no affinity, restart on deploy; its EC2 target is withdrawn but the restart premise is unchanged
- [ADR-016](ADR-016-local-container-runtime.md) — where the process actually runs
- [ADR-017](ADR-017-frontend-delivery-topology.md) — the reverse proxy this ADR left open: Caddy, which needs no buffering directive on the event path
- [ADR-005](ADR-005-error-handling-strategy.md) — why client actions stay HTTP requests
- [ADR-019](ADR-019-session-lifecycle-and-join-codes.md) — the first real emitter, and how the stream is authenticated
- [PRD §3.3](../requirements/PRD-eop-card-game.md) — the game's traffic shape
- [Runtime view](../architecture/runtime-view.md) — the reconnect and SSE sequences drawn out
- EOP-8 (spike), EOP-10 (first use)
