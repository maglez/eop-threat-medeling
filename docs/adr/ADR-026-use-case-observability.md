# ADR-026: Where Use Case Observability Lives

**Status:** Accepted
**Date:** 2026-08-13
**Deciders:** @architecture-guardian, @tech-lead

*(Amended 2026-08-17, EOP-70 — this ADR is now Accepted. Option 4 was chosen: audit logging lives
at the HTTP boundary in `TrickController`, not in the use cases. The three game-affecting write
endpoints — `POST /{sessionId}/deal`, `POST /{sessionId}/plays`, and
`POST /{sessionId}/tricks/current/resolve` — each emit one `INFO` line naming the session UUID and
the first 8 characters of the actor's token. Card identities are never logged (CWE-117 and
hidden-information constraints documented in the Decision section below). The `Logger` field is
`private static final` in `TrickController`. Two use cases carry pre-existing SLF4J imports
(`ResolveTrickUseCase`, added EOP-65; `SweepExpiredSessionsUseCase`) — this is accepted debt, not
a constraint that was enforced before EOP-70. The three write endpoints audited here log at the
HTTP boundary, not inside those use cases. This choice
is adequate while every use case is reached through HTTP, which is currently true. If a use case
is ever invoked from a non-HTTP path, the audit gap reopens and this ADR must be revisited.
ADR-028 is amended to record this predecessor as discharged.)*

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

*(Amended 2026-08-14, EOP-14 Slice E — read the sentence and the list above as the state on
2026-08-13, when this ADR was written; both are kept unaltered as history. There are now **twelve**
use cases, and the twelfth is `GetTrickStateUseCase`, the state-of-play read behind
`GET /api/v1/sessions/{sessionId}/tricks/current`. It logs nothing either, so the decision below is
unchanged in substance and wider by one in scope. Both halves were re-derived rather than assumed:
`ls src/main/java/org/maglez/eop/usecase/ | grep -c 'UseCase.java$'` answers 12, and
`grep -rn 'slf4j\|Logger' src/main/java/org/maglez/eop/usecase/` still matches nothing at all. Where
this ADR says "eleven" below, the arity is stale and the claim it carries is not; the twelve are
enumerated in this ADR's row in [README.md](README.md).)*

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

*(Amended 2026-08-17, EOP-70 — **Option 4 was chosen.** The text below records the original
decision space as written on 2026-08-13; the choice made and the three mandatory answers are
documented in the amendment block at the top of this file and in ADR-040.)*

The obvious implementation is an SLF4J logger in each use case. That would put
`org.slf4j` into `org.maglez.eop.usecase`, and `AGENTS.md` and `.opencode/rules/clean-architecture.md`
require that layer to have no framework imports — which is why `config/UseCaseConfiguration` exists at
all, so that not even a Spring annotation reaches it. Satisfying the observability rule the direct way
would break a rule of longer standing.

Four arrangements survive that constraint:

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
   only while every use case is reached through HTTP. **This is the option chosen (EOP-70).**

The chosen option (4) answers the three mandatory questions as follows:

- **CWE-117 log forging.** `PlayCardCommand` carries player-supplied `notes` and `components`.
  `TrickPlay` already rejects control characters, CR/LF and bidirectional formatting characters on both,
  so the domain type is the existing defence. The audit lines in `TrickController` log only
  `sessionId` (a UUID path variable, Spring-parsed), a truncated token prefix (8 base64url chars,
  no CR/LF possible), an integer trick sequence, an integer play count, and a winning-seat `Integer` — none of
  which are free-text user input. The safety rests on prior authorisation and domain validation,
  not on sanitisation at the log site; this is recorded explicitly rather than inherited by luck.
- **Hidden information.** A hand is private. The audit lines name actors, seats and outcomes only;
  card identities are never logged. `Hands.toString`, `Hand.toString` and `TrickPlay.toString` are
  all written to avoid this leak, and the log arguments were chosen to match that constraint.
- **Correlation identifiers.** The rule requires an MDC value from `X-Correlation-Id` on every log
  line, and `server.forward-headers-strategy` is pinned to `none` per ADR-021. This third question
  is **deferred**: no `logback-spring.xml` exists and no MDC plumbing is in place. The audit lines
  introduced by EOP-70 emit on Spring Boot's default console pattern without correlation IDs or
  structured JSON. This is a known gap, accepted for EOP-70 and recorded here rather than silently
  omitted. A follow-up story must add `logback-spring.xml` (production JSON appender) and an MDC
  filter that reads `X-Correlation-Id` from the request, with the trust boundary for that header
  settled against ADR-021. Until that story ships, the audit trail is present but not
  machine-parseable and not correlated across requests.

## Consequences

*(Amended 2026-08-17, EOP-70 — the bullets below are rewritten to reflect the Accepted state.
The original text is preserved in git history.)*

- **Option 4 is in place.** `TrickController` emits one `INFO` audit line per successful
  game-affecting write (deal, play-card, resolve-trick). The three write endpoints log at the HTTP
  boundary; two use cases (`ResolveTrickUseCase`, `SweepExpiredSessionsUseCase`) carry pre-existing
  SLF4J imports from earlier stories — that is accepted debt, not a constraint enforced before
  EOP-70.
  The obligation recorded when this ADR was `Proposed` is discharged for the three write endpoints
  that are now live.
- **Successful writes only.** The audit lines fire after the use case returns. A rejected write —
  a forged token, an out-of-turn play, a card the player does not hold — produces no audit line.
  `GlobalExceptionHandler` records server faults at `ERROR`; refused writes are silent in the
  audit trail. This is a known limitation of option 4 and is accepted.
- **Correlation identifiers and structured JSON are deferred.** See the Decision section above.
  The audit trail is present but not correlated and not machine-parseable until the follow-up
  story ships.
- **`SessionController` and `CreateSessionUseCase` are out of scope.** The session-lifecycle
  surface (`/sessions`, `/sessions/{id}/join`, `/sessions/{id}/start`) still emits no audit
  logging. That gap is accepted and recorded in ADR-013.
- **The predecessor obligation is discharged.** This ADR is no longer a predecessor of any open
  story. The flag-on story (EOP-70, ADR-040) is the story that discharged it.
- **Nothing enforces the option-4 choice.** The build is green without audit logging, and there is
  no test that fails if a new controller omits it. A future controller author must consult this ADR
  to know the convention. A test that asserts logging exists on write endpoints would be the only
  thing that stops this going stale again.

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
- **ADR-025** (dealing is its own use case) — added three of the twelve unobserved use cases and
  is the slice during which this gap was found. *(The denominator was eleven until EOP-14 Slice E
  added the twelfth; three is unchanged.)*
- **`.opencode/rules/clean-architecture.md`** — the constraint that rules out the obvious
  implementation, and the reason this needs an ADR rather than a commit.
