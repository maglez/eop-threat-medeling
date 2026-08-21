# ADR-040: Enable `eop.features.trick-play` (EOP-70)

**Status:** Accepted
**Date:** 2026-08-17
**Deciders:** @architecture-guardian, @tech-lead, @security-auditor

## Context

`eop.features.trick-play` has been `false` since EOP-14 Slice D added `TrickController` and the
four trick-play routes. ADR-028 (amended by EOP-48) recorded three predecessor gates that had to
be discharged before the flag could be turned on:

1. **ADR-026** (use-case observability) — the game-affecting writes (`/deal`, `/plays`,
   `/tricks/current/resolve`) must not go live with no audit trail.
2. **EOP-48** — a security defect in `TrickController` (caller-supplied seat and card identity
   allowed forged plays). Fixed in commit `34d30d7`.
3. **EOP-15** — the full trick-taking domain (Slice C). Discharged by ADR-032.

All three are now discharged. This ADR records the flag-on decision and the choices made to
discharge ADR-026.

## Decision

### 1. `eop.features.trick-play: true`

`src/main/resources/application.yml` is updated to set `trick-play: true` as the permanent
default. This is a reviewed source change, not an environment override — the audit trail is the
commit history. The flag and its `@ConditionalOnProperty` guards remain in place until the feature
is confirmed stable; removal is a separate story.

### 2. ADR-026 discharged: option 4 — log at the HTTP boundary

ADR-026 documented four arrangements for satisfying the observability rule without importing a
framework into the use-case layer. Option 4 was chosen: audit logging lives in `TrickController`,
at the HTTP boundary, where logging already exists (`GlobalExceptionHandler`).

Each of the three game-affecting write endpoints emits one `INFO` line:

- `POST /{sessionId}/deal` → `audit: deal session={uuid} actor={token-prefix}`
- `POST /{sessionId}/plays` → `audit: play-card session={uuid} actor={token-prefix} trick={n} plays={n}`
- `POST /{sessionId}/tricks/current/resolve` → `audit: resolve-trick session={uuid} actor={token-prefix} trick={n} winner={seat}`

The actor token is truncated to 8 characters. Card identities are never logged — a hand is
private, and `Hands.toString`, `Hand.toString` and `TrickPlay.toString` are all written to avoid
this leak (ADR-026 §hidden-information). The `Logger` field is `private static final` in
`TrickController`; the three write endpoints log at the HTTP boundary. Two use cases
(`ResolveTrickUseCase`, `SweepExpiredSessionsUseCase`) carry pre-existing SLF4J imports from
earlier stories — accepted debt, not a constraint enforced before EOP-70.

This choice is adequate while every use case is reached through HTTP, which is currently true. If
a use case is ever invoked from a non-HTTP path, the audit gap reopens and ADR-026 must be
revisited.

### 3. UI changes

- `LobbyScreen` calls `POST /deal` immediately after `POST /start` succeeds.
- `canStartGame` threshold raised from 2 to 3 to match `GameSession.MINIMUM_PLAYERS_TO_START`.
- `GameScreen.refreshGameState` fetches `GET /sessions/{id}` first; a 404/403 there is
  session-end. A 409 from `/hand` or `/tricks/current` (`HandNotDealtException`) shows a
  "Waiting for cards to be dealt…" state rather than navigating home.

## Consequences

- The full game is now playable end-to-end: deal, play-card and resolve-trick routes are live in
  the default configuration.
- Every **successful** game-affecting write has an attributable audit line. A dispute over who
  dealt or who played what has a log to appeal to. Refused writes (forged token, out-of-turn play,
  card not in hand) produce no audit line — this is a known limitation of option 4 and is accepted
  and recorded in ADR-026.
- Option 4 is the simplest arrangement and the one that requires the fewest new classes. Its
  limitation — that a use case invoked outside HTTP is unobserved — is accepted and recorded.
- **Correlation identifiers and structured JSON are deferred.** The audit lines emit on Spring
  Boot's default console pattern without MDC correlation IDs or structured JSON output. A follow-up
  story must add `logback-spring.xml` (production JSON appender) and an MDC filter for
  `X-Correlation-Id`, with the trust boundary settled against ADR-021. Until that story ships, the
  audit trail is present but not machine-parseable and not correlated across requests. This deferral
  is recorded in ADR-026 §Decision.
- The `LobbyScreen` deal call is non-atomic with the start call: if `startGame` succeeds and
  `dealHands` fails, the session is `IN_PROGRESS` but undealt. There is no retry affordance in the
  current UI — the `dealHands` call site in `LobbyScreen` is unmounted once the SSE event fires
  `onGameStarted`, so a failed deal leaves the session permanently undealt with no path back to a
  deal call. `GameScreen` shows a "Waiting for cards to be dealt…" banner in this state, which is
  accurate but unrecoverable without a facilitator-side retry feature. A retry affordance is a
  known limitation accepted for this story and should be addressed in a follow-up.
- `SessionController` and `CreateSessionUseCase` still emit no logging. The ADR-026 gap on the
  session-lifecycle surface is accepted (recorded in ADR-013) and is not addressed here.

## Relations

- **ADR-026** (use-case observability) — discharged by this story; status changed to Accepted.
- **ADR-028** (end-of-hand without release or score) — amended to record all three predecessors
  as discharged.
- **ADR-013** (feature flags) — amended to record `trick-play: true`.
- **ADR-027** (singleton subresource naming) — amended to record the flag position change.
- **ADR-037** (frontend build-time feature flags) — `VITE_GAME_SCREEN_ENABLED` documented in
  `ui/.env.example`.
