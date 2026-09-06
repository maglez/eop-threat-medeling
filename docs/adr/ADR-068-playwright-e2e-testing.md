# ADR-068: End-to-end testing with Playwright, a dedicated `e2e/` directory and a standalone `compose.e2e.yml`

**Status:** Accepted

**Date:** 2026-09-06

**Deciders:** @tech-lead, @devops-engineer, @tester-api

## Context

This repository had three tiers of automated test and a gap where a fourth should be.

JUnit unit tests cover the domain with no Spring context. Spring integration tests
cover the HTTP API, the exception mappings and the rate limiters. The k6 canary covers
latency and throughput. All three are valuable and none of them opens a browser.

That gap is not academic. The front end is a React SPA that talks to the API over
`fetch`, restores player identity from `sessionStorage`, and re-fetches state when an
SSE doorbell rings (ADR-014). It is served by Caddy from an image with a baked-in
`Caddyfile` that sets a restrictive CSP, refuses source maps, caps request bodies at
16 KB and falls back to `index.html` for unknown paths. Every one of those behaviours
is invisible to the existing tiers:

- A Vitest test in `ui/` stubs `fetch`. It proves a component renders given a payload;
  it cannot prove the payload ever arrives, or that the bundle loads at all.
- A Spring integration test calls a controller. It never evaluates JavaScript, so a
  CSP that blocks the bundle, an asset path that 404s, or a `sessionStorage` key that
  is read under the wrong name all pass it cleanly.
- The CI smoke test curls `/health` and the card endpoint. It proves the stack is up,
  not that the application is usable.

The concrete request that prompted this work was for evidence: a report that can be
handed to a stakeholder as support that the application passes its functional tests.
No existing tier produces that, because none of them tests the application the way a
user meets it.

### The reconnect mechanic, which motivated part of the scope

Investigation during EOP-216 established how player identity persists, because the
boundary scenarios depend on it. `App.tsx` writes `{playerToken, playerId, sessionId}`
into `sessionStorage` under the key `eop_session`, and restores the lobby screen on
load when that value is present and valid. `sessionStorage` is **tab-scoped and
cleared when the browser closes**, so a returning player must re-join by join code and
display name and receives a new `playerToken`. Server-side session state is preserved
independently. That asymmetry — server remembers, client forgets — is exactly the kind
of behaviour only a browser test can verify, and it is why browser-close reconnect is
a named scenario in EOP-218 rather than an assumption.

## Decision

Adopt **Playwright** (`@playwright/test`) as the fourth test tier, in a new top-level
`e2e/` directory, driven against a new standalone `compose.e2e.yml`.

### `e2e/` at the repository root, not inside `ui/`

The suite tests the assembled system — Caddy, the API and Postgres together — not the
front-end package. Placing it in `ui/` would make it a dependency of the front-end
build, put container orchestration inside `npm run verify`, and imply the wrong scope.
It is a sibling of `test/k6/` conceptually, and a sibling of `ui/` structurally.

It carries its own `package.json`, `package-lock.json` and `tsconfig.json`. Node
floor is `>= 22.12`, the repository floor set by `ui/package.json`.

### Test against the shipped images, unmodified

The compose file runs the production app image built from the root `Dockerfile` and
the production UI image built from `ui/Dockerfile`, with its own `Caddyfile` baked in.
Nothing is bind-mounted over, and no test-only assembly exists.

This is the decision that gives the tier its value. Because the real `Caddyfile` is in
play, the security headers (ADR-035), the 16 KB body cap (ADR-033), the source-map
refusal (EOP-107) and the SPA fallback are all under test. A test-only web server
configuration would have tested none of them, and would have drifted from the shipped
one within weeks.

The one build-time difference is unavoidable and is a *flag*, not a code change: the
UI image is built with `--build-arg VITE_GAME_SCREEN_ENABLED=true` and tagged
`eop-ui:e2e`. `ui/Dockerfile` defaults all three `VITE_*` flags to `false`
(fail-closed, ADR-037), and those flags are substituted into the bundle at build time,
so there is no runtime override. A distinct tag makes the difference visible in
`docker images` rather than leaving two same-named images with different behaviour.

### TLS, not plain HTTP

EOP-216 was originally specified with a plain-HTTP test stack, and an acceptance
criterion that no `--ignore-https-errors` flag would be needed. **That premise was
false and the decision was reversed during delivery.**

`ui/Caddyfile` sets `auto_https disable_redirects` and its only site block serves
`tls internal`. Nothing listens on plain HTTP, and that Caddyfile is `COPY`d into the
image — so plain HTTP is not a configuration choice, it is absent from the artefact.
Obtaining it would have required one of:

1. Editing `ui/Caddyfile` — changes production, which the story forbade.
2. Bind-mounting a second, divergent Caddyfile — abandons the central benefit above,
   leaves four shipped behaviours untested, and guarantees drift between two files.
3. Building a separate UI image — the same divergence with more machinery.

Instead the suite sets `ignoreHTTPSErrors: true`. This is not a new concession; it is
the trade the repository already makes in four places: `curl -fsSk` in the CI smoke
test (twice), `--insecure-skip-tls-verify` in the k6 canary, and
`wget --no-check-certificate` in the UI image's own `HEALTHCHECK`.

Plain HTTP would also have been actively hazardous. `ui/Caddyfile` sets HSTS with a
two-year `max-age` and `includeSubDomains`, and **HSTS on localhost is port-agnostic**
— so a plain-HTTP listener on any localhost port would be force-upgraded in a
developer's own browser for two years. Playwright's fresh profiles dodge this; a human
cannot.

### Addressing: `https://localhost:8443`

Each component is constrained rather than chosen.

**Hostname must be `localhost`.** Caddy aborts the handshake server-side on a
non-matching non-empty SNI, and `default_sni` does not rescue it. A client-side
insecure flag cannot help, because the failure is on the server, not the client. This
was established in EOP-160 (amending ADR-055), which eliminated four alternatives —
service name, container IP literal, a `localhost` network alias, and a Compose sidecar
— before settling on addressing the target as `localhost`.

**Host port must be neither 443 nor 8080.** `compose.app.yml` publishes 443, and a
local `./mvnw spring-boot:run` binds 8080. Publishing on 8443 keeps all three usable
at once.

**Publishing 8443 to a container listening on 8080 is safe**, because Caddy matches a
site block on hostname alone and ignores the port. This was verified empirically here
and is already load-bearing in CI, whose smoke test curls `https://localhost/health`
on host port 443 against the same `localhost:8080` site block and passes.

### A standalone compose file, not an override

`compose.e2e.yml` is a third stack beside `compose.app.yml` and `docker-compose.yml`.
An override file (`-f compose.app.yml -f compose.e2e.yml`) would inherit the project
name, network and volumes, so starting the tests would tear down a developer's running
application and delete its database.

Isolation is therefore total: project `eop-e2e`, subnet `172.29.0.0/24`, volumes
prefixed `eop_e2e_`, containers prefixed `eop-e2e-`, host port 8443.

Two deliberate divergences from `compose.app.yml`:

- **Postgres credentials are defaulted, not `:?` fail-if-unset.** The database is
  throwaway and destroyed by `down -v`. Requiring `.env` would block a fresh clone
  from running the suite and would force a CI secret for a container that exists for
  40 seconds.
- **Caddy depends on the app's *healthcheck*, not merely its start.** `up -d --wait`
  returns when healthchecked services are healthy, and that is the signal
  `global-setup` relies on. Ordering alone would let it return while `/api` still 502s.

### Lifecycle: `globalSetup` starts the stack, `globalTeardown` destroys it

`global-setup.ts` runs `docker compose up -d --wait --remove-orphans`, then polls
`https://localhost:8443/health` from the host until it returns 200 with a trimmed body
of exactly `OK`. `global-teardown.ts` runs `down -v --remove-orphans`.

The host-side poll is **not** redundant with `--wait`. That flag proves each
container's internal healthcheck passed — the app probes itself on
`http://127.0.0.1:8080/health`, the UI image probes itself on
`https://localhost:8080/index.html` — which says nothing about host port publication
or the TLS handshake the browsers depend on. And the body is compared, not just the
status, because a Caddy that matched no site block answers `200` with an empty body:
the single most likely misconfiguration is invisible to a status-only check.

`-v` on teardown is a requirement rather than tidiness. A fresh database per run is
what makes the leaderboard scenarios in EOP-219 deterministic.

Three environment escape hatches exist — `E2E_REUSE_STACK`, `E2E_KEEP_STACK`,
`E2E_BASE_URL` — parsed strictly, accepting only `1`/`true`/`yes`/`on` and failing
closed on anything else, so a typo disables a hatch rather than enabling it.
`E2E_REUSE_STACK` skips both `up` and teardown but **never** the health wait; CI uses
it so that CI owns the stack and can collect container logs after a failure, which a
teardown inside the test run would have deleted.

### Three browsers, run serially

Chromium, Firefox and WebKit. WebKit earns its place as the only available proxy for
Safari, whose `EventSource` and `sessionStorage` behaviour is where this application
is most likely to diverge — and both are load-bearing here.

`fullyParallel: false`, `workers: 1`. There is one application instance and one
database, the leaderboard reads whole-session history (ADR-030), and the binding
constraint is that both rate limiters key on the resolved client address, which is
identical for every browser the suite launches. Parallel workers would contend for one
bucket and fail nondeterministically.

This is a starting position. Relaxing it means opting individual files in with
`test.describe.configure({ mode: 'parallel' })` and measuring, not flipping the global
switch.

`retries: 1` in CI only, and a retried pass reports as **flaky** rather than passed,
so instability is surfaced instead of absorbed.

### Rate limits are raised in the test stack, and that bounds what the tier proves

`compose.e2e.yml` sets `EOP_WEB_SESSION_CREATION_LIMIT` and
`EOP_WEB_READ_RATE_LIMIT_LIMIT` to `Integer.MAX_VALUE`.

Caddy forwards `X-Forwarded-For: {remote_host}` and the app is told to trust it, so
every browser resolves to the one host address and the entire suite shares a single
bucket. At shipped values that is 5 session creations and 300 reads per 60 seconds for
the whole run, against a two-player game that alone peaks near 100 reads a minute
(ADR-051). This mirrors what `src/test/resources/application.properties` already does
for the Java integration tests, for the same reason.

**Consequently this tier must never be cited as evidence that rate limiting works.**
The limiters still execute and still count — the ceiling moved, the code path is not
bypassed — but the production thresholds are verified by the Java integration tests,
which pin a low limit per class with `@DirtiesContext`. `max-tracked-keys` is left at
its shipped value because the suite produces exactly one key.

### Quality gates for `e2e/`

`tsconfig.json` mirrors `ui/`'s full strictness block — `strict`,
`noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch`,
`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` — and `npm run typecheck` is
wired into `npm run verify`.

Two deliberate divergences, recorded here so neither reads as an oversight:

- **`module: CommonJS`, and no `"type": "module"` in `package.json`.** Playwright's
  loader emits CommonJS, which is what lets `__dirname` resolve in `stack.ts`.
  Declaring ESM would typecheck a module system the suite does not run under, moving a
  `__dirname` failure from build time to run time. `verbatimModuleSyntax` is omitted
  for the same reason — it is incompatible with `module: CommonJS` here.
- **No ESLint.** `ui/` has one; this directory does not. The strict compiler is the
  gate. Adding ESLint later is cheap and uncontroversial; asserting it is present
  when it is not would be worse than the gap.

### Scope deliberately not taken

- **The suite is not part of `./mvnw verify`.** It needs a container runtime and
  browser binaries, and it takes tens of seconds. Coupling it to the Maven build would
  make every unit-test run depend on Docker. CI strategy is ADR-069's subject.
- **No visual regression or screenshot diffing.** Screenshots are captured on failure
  as diagnostics only. Pixel baselines across three browser engines are a maintenance
  burden with a poor signal-to-noise ratio, and the report this tier exists to produce
  is functional evidence, not visual.
- **No accessibility assertions in this tier.** `ui/` already tests accessible roles
  and names with Testing Library, and the suite queries by role, so a broken
  accessibility tree tends to fail a functional test anyway. A dedicated axe pass is a
  reasonable future story, not part of the scaffold.
- **No Playwright MCP server.** Standard `@playwright/test` as a dev dependency. The
  suite is CI machinery, not an interactive agent tool.

## Consequences

**Positive**

- The shipped artefacts are tested as assembled, including four `Caddyfile`
  behaviours that previously had no automated coverage at all.
- A real browser executes the real bundle, so a CSP regression, a broken asset path or
  a `sessionStorage` contract change fails a test instead of reaching a user.
- The suite is self-contained and reproducible: one command, no manual setup, nothing
  left behind, and a fresh database every run.
- The three-browser matrix gives genuine WebKit coverage, which no other tier has.
- The HTML report is the stakeholder-facing functional evidence the tier was asked
  for.

**Negative, and accepted**

- A container runtime and roughly 500 MB of browser binaries are now needed to run one
  of the four tiers. Documented in `e2e/README.md`; not required for `./mvnw verify`.
- Serial execution means wall-clock time is roughly the sum across three browsers.
  Accepted for now, with a measured path to relaxing it.
- The suite runs with rate limits effectively disabled, so it cannot testify about
  throttling. Stated in the compose file's comments, in `e2e/README.md` and above.
- Two UI image tags now exist with different flag settings. The distinct `:e2e` tag
  makes this visible, but it is a thing to know.
- `ignoreHTTPSErrors: true` means the suite does not verify the certificate. This is a
  local CA with `tls internal`; there is no certificate worth verifying, and the same
  trade is already made four times elsewhere.
- A fourth Node package (`e2e/`) joins `ui/` and `tools/graphify/`, each with its own
  lockfile. `tools/supply-chain/` does not cover it, matching the existing position on
  the Maven layer and on `ui/`.

## Related

- `compose.e2e.yml` — the standalone test stack
- `e2e/README.md` — how to run it, and every constraint restated operationally
- `e2e/playwright.config.ts`, `e2e/stack.ts`, `e2e/global-setup.ts`, `e2e/global-teardown.ts`
- `e2e/tests/smoke.spec.ts` — the only scenario in the scaffold story
- ADR-069 (not yet written, due with `EOP-220`) — how this suite runs in CI and publishes its report
- [ADR-014](ADR-014-realtime-transport.md) — the SSE doorbell the suite waits on
- [ADR-017](ADR-017-frontend-delivery-topology.md) — single origin, which is why one Caddy fronts both
- [ADR-033](ADR-033-session-creation-rate-limit-and-body-size-cap.md) — the body cap and creation limiter
- [ADR-035](ADR-035-tls-and-security-response-headers.md) — the headers now under browser test
- [ADR-037](ADR-037-frontend-build-time-feature-flags.md) — why the `--build-arg` is required
- [ADR-051](ADR-051-read-route-rate-limit.md) — the read limiter and its per-minute arithmetic
- [ADR-055](ADR-055-k6-performance-check-in-ci.md) — as amended by EOP-160, source of the SNI constraint
- Epic `EOP-215`; stories `EOP-216` (this scaffold), `EOP-217`, `EOP-218`, `EOP-219`

## Amendment, 2026-09-06 (EOP-217)

Three findings from the EOP-217 happy-path delivery contradict premises the ticket was written on, and are recorded here because they constrain every future E2E scenario. They keep the numbering of the EOP-217 pre-delivery comment, so the sequence below is 1, 3, 4: findings 2, 5 and 6 there concerned the mechanics of one story — which image carries `VITE_GAME_SCREEN_ENABLED`, how a reload re-enters the game screen, and the re-scoping of a single scenario — and constrain no later work, so they are not repeated here. A fourth finding, on the leaderboard's persistence window, was established during the gate round and is recorded below as an addendum.

### Finding 1 — minimum player count is three, not two

The epic and the ticket both assumed a two-player happy path. `GameSession.java:38` declares
`MINIMUM_PLAYERS_TO_START = 3` and `GameSession.java:246-247` enforces it by throwing
`TooFewPlayersException`, mapped to HTTP 409 in `GlobalExceptionHandler.java:308-309`. The front end
agrees independently: `LobbyScreen.tsx:37` hard-codes `session.players.length >= 3` to enable the
start control. The maximum is six (`GameSession.java:35`).

**Consequence for the suite:** the happy-path scenario is a three-player game, not two. The two
independent hard-coded 3s are a drift hazard worth naming.

### Finding 3 — a full game is 68 card plays, and the opening leader is not seat 0

The entire 68-card printed deck is dealt and nothing is discarded (`Hands.java:169-176`; its
javadoc at `Hands.java:106` gives the three-player split as 23/23/22). Three players means 23 tricks
and 68 plays. The *play* count is invariant at 68 for any seat count, while the *trick* count varies
(17 at four players, 12 at six). There is no shortcut to the terminal screen — there is no
`endSession` function in `ui/src/api.ts` and no such control in any component — so the E2E happy
path must genuinely play all 68 cards, which is why that scenario raises its own timeout with
`test.setTimeout()` rather than relying on the 60-second global in `e2e/playwright.config.ts`.

The opening leader is whoever was dealt the lowest-ranked Tampering card (`Hands.java:216-233`),
**not** seat 0, so the test must discover whose turn it is rather than assume.

### Finding 4 — follow-suit is enforced server-side only, and the UI does not prevent an illegal play

`Trick.java:208-215` enforces follow-suit, and trump grants no exemption. The UI never disables
illegal cards: `GameScreen.tsx:815` uses `disabled={!isMyTurn || isPlayingCard}` and never consults
suit.

**Consequence for the suite:** a test driving only the UI must itself choose a legal card, which is
why `e2e/game.ts` has a `chooseLegalCard` helper that reads the led suit from the trick zone. A
stale read is harmless specifically when leading, because leading permits any card.

### Addendum — the leaderboard is not readable the instant the game ends

Established during EOP-217's gate round rather than before delivery, and recorded because it
constrains every future scenario that asserts on the game-over screen.

Completing a game and recording its result are two steps, and only the first is guaranteed.
`TrickJournal` marks the session completed (`TrickJournal.java:173`), then persists the final
standings on a best-effort basis — the call is wrapped so that a `RuntimeException` is logged and
swallowed (`TrickJournal.java:180-186`) — and then publishes `GAME_COMPLETED`
(`TrickJournal.java:193`, anchor: `GAME_COMPLETED`) whether or not that persist succeeded. A client
woken by that event may therefore read the leaderboard before the result row exists, and
`GetLeaderboardUseCase.java:81` (anchor: `GameResultNotRecordedException`) throws
`GameResultNotRecordedException` for exactly that case, which `GlobalExceptionHandler.java:1017`
maps to **404** — deliberately the same status an absent session gets.

So a 404 from the leaderboard is a legitimate transient, not a failure. The front end already treats
it as one, offering a `Retry loading results` control
(`GameOverScreen.tsx:207`, anchor: `Retry loading results`).

**Consequence for the suite:** `expectGameOver` (`e2e/game.ts:411`) asserts the `Game over` heading
first, because that heading renders unconditionally, and only then looks for the leaderboard table —
falling back to the screen's own retry control. The distinction that keeps this honest is that the
fallback is conditional on the retry control being present: a genuine 500, or a selector that has
rotted, produces no retry control and the original failure is re-thrown rather than being retried
into a false pass.

### What these mean for the design

ADR-068 designed a suite that drives the application only through the browser. These findings are
all consequences of that choice meeting the real domain: the suite must discover state (whose turn,
which suit) rather than assume it, must respect real domain minimums, and must treat a
legitimately-transient error as transient without blunting a real one.
