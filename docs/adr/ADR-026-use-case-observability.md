# ADR-026: Where Use Case Observability Lives

**Status:** Proposed
**Date:** 2026-08-13
**Deciders:** @architecture-guardian, @tech-lead

## Context

`.opencode/rules/observability.md` is a binding project rule. It requires SLF4J only and never
`System.out`, structured JSON via `logback-spring.xml` for production profiles, an MDC correlation
identifier taken from an `X-Correlation-Id` header on every line, `INFO` at operation boundaries and
`WARN` for validation failures **in the application and use case layer**, and — named explicitly —
"audit logging for game-affecting actions (card draws, privilege escalations) at `INFO` level with
actor context".

No use case in this repository logs anything. Not one of the eleven:

`CreateSessionUseCase`, `DealHandsUseCase`, `GetCardUseCase`, `GetSessionStateUseCase`,
`JoinSessionUseCase`, `ListCardsUseCase`, `PlayCardUseCase`, `ReadOwnHandUseCase`,
`ResolvePlayerUseCase`, `ResolveTrickUseCase`, `StartSessionUseCase`.

Dealing a hand, playing a card and resolving a trick are game-affecting actions by any reading, so
the three added by EOP-14 Slice C2 are squarely inside the rule. So are the seven that preceded them.
Logging in this codebase currently lives in the web adapter, where `GlobalExceptionHandler` records
server faults at `ERROR` and `WinningPlayNotInTrickException` at `WARN`.

EOP-14 Slice D then added the eleventh use case, `ReadOwnHandUseCase`, and `TrickController`: the
first HTTP routes through which dealing, playing and resolving can actually be invoked. That slice
deliberately left the gap alone. Putting a logger in `TrickController` would have adopted option 4
below by default, in a feature slice, without weighing the other three — and it would have made this
one controller the only observed entry point in the application, which is exactly the non-uniformity
the consequences section gives as the reason the gap is currently tolerable. The routes existing does
raise the stakes, though: until this ADR is resolved, the actions are now reachable *and* unobserved.

Three review gates raised this against Slice C2 — `@tester-unit-and-quality` as a finding,
`@code-reviewer` as its single warning, and `@architecture-guardian` as a MAJOR — and all three
judged it a repository-wide gap rather than a defect in that slice. The first two treated it as a
follow-up. The third accepted that disposition but refused to let it rest only in review messages
and a ticket, on the grounds the repository applies elsewhere: **nothing fails while this is
missing, and the build is green without it, so a record that lives outside the repository will be
lost.** This ADR is that record.

## Decision

Nothing is decided yet. This ADR is `Proposed` and exists to hold the obligation and to state the
design question that has to be answered before any code is written, because the obvious
implementation is the wrong one.

The obvious implementation is an SLF4J logger in each use case. That would put
`org.slf4j` into `org.maglez.eop.usecase`, and `AGENTS.md` and `.opencode/rules/clean-architecture.md`
require that layer to have no framework imports — which is why `config/UseCaseConfiguration` exists at
all, so that not even a Spring annotation reaches it. Satisfying the observability rule the direct way
would break a rule of longer standing.

At least four arrangements survive that constraint and should be weighed:

1. **A logging port.** Declare an interface in `usecase` and implement it in an adapter. Honest about
   the dependency, but it puts an observability concern into the domain's vocabulary and every use case
   grows a collaborator that has nothing to do with its behaviour.
2. **A decorator per use case, declared in `config`.** The bean method wraps the plain use case in a
   logging decorator. Keeps `usecase` clean and keeps the container as the only place that knows about
   logging, at the cost of one small class per use case.
3. **An aspect or interceptor in the outer ring**, matched on the use-case package. Cheapest to write
   and the easiest to make inconsistent, since what gets logged stops being visible at the call site.
4. **Log at the boundary only** — in controllers and in `GlobalExceptionHandler`, where logging already
   lives — and accept that a use case invoked from anywhere else is unobserved. Simplest, and adequate
   only while every use case is reached through HTTP.

Whichever is chosen must also answer three things the rule raises but does not settle for this domain:

- **CWE-117 log forging.** `PlayCardCommand` carries player-supplied `notes` and `components`.
  `TrickPlay` already rejects control characters, CR/LF and bidirectional formatting characters on both,
  so the domain type is the existing defence — but a decision to log request-derived text has to say so
  explicitly rather than inherit it by luck.
- **Hidden information.** A hand is private. An `INFO` line naming dealt cards would put every hand in
  the log, which is the same leak `Hands.toString`, `Hand.toString` and `TrickPlay.toString` are all
  written to avoid. Audit lines must name actors, seats and outcomes, never card identities.
- **Correlation identifiers.** The rule wants an MDC value from `X-Correlation-Id`, and
  `server.forward-headers-strategy` is pinned to `none` per ADR-021, so where that header is trusted from
  is a decision with a security edge, not a plumbing detail.

## Consequences

- The obligation is now recorded somewhere that outlives a review conversation, and it names all eleven
  use cases so a future reader does not have to rediscover the scope.
- It stays outstanding, and it stays outstanding **uniformly**: no use case logs, so no use case is the
  odd one out. Adding logging to only the three from Slice C2 would have made the codebase less
  consistent, which is the reason all three gates declined to require it there.
- Nothing enforces this. The build is green without it, and there is no test that fails while a use case
  is silent. If it matters, the work needs its own story; a test that asserts logging exists would be
  the only thing that stops this ADR going stale in place.
- Until it is resolved, an operator has no record of who dealt, who played what, or who resolved a
  trick — which is the actual cost, and the reason this is `Proposed` rather than closed.
- **Resolving this ADR is a predecessor of the story that turns `eop.features.trick-play` on, not a
  parallel backlog item.** EOP-14 Slice D added the four routes through which dealing, playing and
  resolving can be invoked, so those three writes are now reachable *and* unobserved; while the flag is
  `false` the controller bean does not exist and there is nothing to audit, but the moment it is `true`
  the product's first competitively meaningful writes go live with no evidence of who made them. A
  dispute over who played what would have nothing to appeal to. `@security-auditor` made that ordering
  a condition of approving Slice D, and it is recorded here rather than left in a review transcript
  because, as this ADR says above, a record that lives outside the repository will be lost.

## Alternatives considered

- **Fix it inside EOP-14 Slice C2.** Rejected by all three gates that raised it. The slice's three use
  cases are exactly as compliant as the seven that shipped through earlier approved gates, and a gate
  that blocks the tenth instance of a pattern it approved nine times is applying a standard
  retroactively. The fix also does not fit in a use-case slice's diff: it needs a `logback-spring.xml`
  production profile and correlation-id plumbing in the web layer.
- **Record it in Jira only.** Rejected for the reason `@architecture-guardian` gave: nothing in the
  repository fails while this is missing, so a ticket is the one place it can be lost silently.
- **Amend `.opencode/rules/observability.md` to exempt the use-case layer.** Rejected as premature. The
  rule's requirement is reasonable and the audit-trail need is real; what is unresolved is *where* the
  logging lives, not *whether* it should exist.

## Relations

- **ADR-005** (RFC 9457 error handling) — `GlobalExceptionHandler` is where logging exists today, and
  option 4 above would make that the only place it ever exists.
- **ADR-021** (trusted proxies and `Forwarded-For`) — constrains where an `X-Correlation-Id` header may
  be trusted from, so the MDC requirement cannot be settled without it.
- **ADR-025** (dealing is its own use case) — added three of the eleven unobserved use cases and
  is the slice during which this gap was found.
- **`.opencode/rules/clean-architecture.md`** — the constraint that rules out the obvious
  implementation, and the reason this needs an ADR rather than a commit.
