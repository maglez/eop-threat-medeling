# ADR-042: Enable `eop.features.game-over`, and the shipped-flag-default trap (EOP-82)

**Status:** Accepted
**Date:** 2026-08-18
**Deciders:** @architecture-guardian, @tech-lead, @security-auditor

## Context

EOP-65 built the entire game-over slice — `GetLeaderboardUseCase`, `PersistGameResultUseCase`,
`NewGameUseCase`, `GameOverController` and the `game_result` / `game_result_player` tables — behind
`eop.features.game-over` and merged it with the flag `false`, which is exactly what
`.opencode/rules/feature-flags.md` requires ("merge to `main` with flag OFF", "default all flags to
`false`"). Nothing ever flipped it. The decision record for that slice is ADR-039; the flag itself
was never catalogued in ADR-013, though `feature-flags.md` mandates recording each flag "in
`application.yml` next to its default **and in ADR-013**".

Shipping the feature dark and leaving it dark had two visible consequences and one invisible one.

1. **The leaderboard route answered a framework 404, outside RFC 9457.** With the flag off
   `GameOverController` was never a bean, so `GET /api/v1/sessions/{id}/leaderboard` was not a
   mapped handler at all. Spring fell through to the static-resource handler, which produced
   `{"detail":"No static resource api/v1/sessions/…/leaderboard"}` with content type
   `application/json`. `GlobalExceptionHandler` never saw the request, so
   `.opencode/rules/error-handling.md` ("all API error responses follow RFC 9457 Problem Details,
   `application/problem+json`") was violated by a route that had no code in it to violate it.

2. **No `game_result` row was ever written.** `PersistGameResultUseCase` is gated on the same flag,
   so the persistence path EOP-65 built, migrated and tested was dead in production. The
   `game_result` and `game_result_player` tables existed and stayed empty.

3. **A compounding client fault turned a transient gap into permanent ejection.**
   `GameOverScreen.loadLeaderboard` branched on `err.status === 403 || err.status === 404` and
   called `onSessionEnd()` for both. `onSessionEnd()` clears the session token from
   `sessionStorage`, and that token cannot be reissued (ADR-015) — a player who loses it has lost
   their seat. A 404 from the leaderboard does not mean the session is gone; it means the
   `game_result` row has not been written yet, which is a live race between the game-completed SSE
   event and the asynchronous best-effort persist call in `ResolveTrickUseCase`. The client was
   destroying an unreissuable credential in response to a data gap that would have resolved itself
   in milliseconds.

**Why no test caught the flag position.** `src/test/resources/application.properties` pins all
three feature flags `true` (lines 17, 22 and 27), which `feature-flags.md` also requires — "a suite
running with the feature off would be testing its absence". Every `@SpringBootTest` in the suite
therefore exercised flag-ON behaviour while `application.yml` shipped `false`. No assertion made
through the Spring `Environment` could ever have detected the divergence, because in the test
`Environment` the shipped value is precisely the value that has been overwritten. This is the same
trap EOP-27 removed for `spring.h2.console.enabled`, recorded in the
`**Amendment, 2026-08-10 (EOP-27).**` block of
[ADR-008](ADR-008-database-migration-liquibase.md) — but EOP-27's remedy, *stop pinning the
property in test resources*, is not available here, because the rules mandate that flags are pinned
ON in tests.

## Decision

### 1. `eop.features.game-over: true` is the permanent shipped default

`src/main/resources/application.yml` sets `game-over: true` (line 141). This is a reviewed source
change, not an environment override — the audit trail is the commit history. That distinction is
the point: ADR-013's recorded weakness is that an environment override has **no** audit trail
beyond the shell history of whoever set it, so the default is moved in the source file where review
and `git log` apply.

`src/main/resources/application-prod.yml` carries no `eop.features` block at all, so the
default-profile value is authoritative under `SPRING_PROFILES_ACTIVE=prod` — which is how both the
local container and the deployed one run (ADR-012). There is nothing to change in the `prod`
overlay, and no `EOP_FEATURES_GAME_OVER` entry is needed in `compose.app.yml`: the default is now
`true`, and the env var would only be needed to override it back to `false`.

The flip activates `GetLeaderboardUseCase`, `PersistGameResultUseCase` and `NewGameUseCase`
(`UseCaseConfiguration` lines 433, 452 and 478) and both `GameOverController` routes —
`GET /api/v1/sessions/{sessionId}/leaderboard` and `POST /api/v1/sessions/{sessionId}/new-game`.
The leaderboard route now resolves to a mapped handler, so its refusals travel through
`GlobalExceptionHandler` as `application/problem+json` and the RFC 9457 violation described above
disappears as a side effect of the flip rather than needing a fix of its own.

The `@ConditionalOnProperty(prefix = "eop.features", name = "game-over", havingValue = "true")`
guard on `GameOverController` (line 33) and the three dotted-spelling equivalents on the use-case
beans **stay**, as does the OFF-position test
`GameOverControllerDisabledIntegrationTest`, whose `properties = "eop.features.game-over=false"`
override is now the only place in the suite where this flag's OFF position is exercised at all —
`TrickPlayDisabledIntegrationTest` and `SessionControllerDisabledIntegrationTest` do the same job
for the other two flags. Removal of
the flag and its guards is a separate story, with an expiry condition stated under Consequences
rather than left as another open-ended promise.

### 2. A leaderboard 404 is a data gap, not a session end

`GameOverScreen.loadLeaderboard` now calls `onSessionEnd()` on **403 only**. A 404 leaves the
session token intact, surfaces the error, and renders a `Retry loading results` button
(`govuk-button govuk-button--secondary`) that re-invokes the load.

The asymmetry is worth recording because the *code shape* being narrowed here is still correct
elsewhere: `LobbyScreen` branches on `403 || 404` in both `refreshSession` and the SSE `onError`
handler, and that is right, because on the session resource a 404 genuinely means the session no
longer exists (`docs/architecture/runtime-view.md`, "The custody and recovery path", documents that
path and remains accurate). The same two status codes mean different things on the two resources.
Copying the pattern across resources without re-deriving what each status means on the new resource
is the defect, not the pattern itself.

403 still ejects, and deliberately: an expired or forged token is not a transient gap, and failing
closed on it is the fail-securely rule.

### 3. Shipped flag defaults are asserted without going through the `Environment`

`ShippedFeatureFlagDefaultsTest` reads `application.yml` off the classpath directly, via
`YamlPropertiesFactoryBean` over `new ClassPathResource("application.yml")`, with no Spring context
at all, and asserts the shipped value of each flag. Reading the file rather than the `Environment`
is the whole mechanism: it is the only vantage point from which the test-resource overrides are not
in the way.

This is an **interim workaround, not the architectural answer**. See Consequences.

> **Superseded, 2026-08-23 (EOP-84).** `ShippedFeatureFlagDefaultsTest` has been deleted and
> replaced by `FeatureFlagRegistryTest`, which keeps the classpath-read technique described above —
> that part was always sound — but derives the flag set from the shipped YAML and from the compiled
> `@ConditionalOnProperty` sites instead of naming keys by hand, and holds both against a registry
> of declared intent. See [ADR-053](ADR-053-feature-flag-registry-build-gate.md).

### 4. The flag is catalogued in ADR-013 for the first time

[ADR-013](ADR-013-feature-flags.md) is amended with a `game-over` entry, in the same dated shape as
its EOP-25 (`session-lifecycle`) and EOP-70 (`trick-play`) amendments. The amendment states
explicitly that the flag was omitted at EOP-65, so the catalogue is now complete for the first
time since the third flag was introduced.

## Consequences

- The game-over surface is live in the default configuration: the leaderboard and new-game routes
  answer, and `game_result` rows are written as games complete. The leaderboard's refusals are RFC
  9457 Problem Details, because the route is now a mapped handler.
- A leaderboard read that arrives before the result row is written costs the player one button
  press instead of their seat.
- **The masking trap that hid this is structural and has not been removed.** The rules require
  flags pinned ON in test resources; they will therefore keep masking the shipped default of every
  future flag. EOP-27's fix for the equivalent H2-console trap — remove the pin — is unavailable
  here by construction. Any future guard must read the shipped artefact, not the `Environment`.
- **Negative — the mitigation is weaker than the precedent it is modelled on.** EOP-27's
  `H2ConsoleAbsentIntegrationTest` put its load-bearing assertion on the autoconfiguration class
  itself, so the build fails the day the module lands, whatever anyone remembers to write.
  `ShippedFeatureFlagDefaultsTest` asserts three **hand-named** keys. A fourth flag is invisible to
  it: a fifth feature merged dark, forgotten, and shipped `false` would reproduce EOP-82 exactly
  with the suite green. The test pins today's three positions; it does not detect an undeclared
  flag, and it is not a tripwire. **Closed, 2026-08-23 (EOP-84):** the derived guard in
  [ADR-053](ADR-053-feature-flag-registry-build-gate.md) is the tripwire this bullet asked for. A
  flag with no registry entry now fails `./mvnw verify`, so the fifth feature merged dark cannot
  ship `false` with the suite green.
- **The structural fix is specified but deliberately not implemented here.** Derive the assertion
  from the set of flags that *exist* rather than from a hand-written list — either every key under
  `eop.features` in the shipped YAML, or every `@ConditionalOnProperty(prefix = "eop.features")` on
  the classpath — and check that set against a registry that declares, per flag, its intended
  shipped state, its owning story and its expiry. A flag present in the code but absent from the
  registry fails the build. That inverts the default from "silent unless someone remembers to add a
  line" to "fails until someone declares intent", which is what makes it a tripwire rather than a
  snapshot. This is filed as a separate ticket; EOP-82 ships the snapshot. **Implemented, 2026-08-23
  (EOP-84):** built as specified, and with both enumeration routes rather than either — the shipped
  YAML keys, the bytecode keys and the registry entries are held in three-way agreement, so an
  annotation without a declared default fails as loudly as a YAML key without an entry. See
  [ADR-053](ADR-053-feature-flag-registry-build-gate.md).
- **Negative — three flags flipped on, zero flags ever removed.** `session-lifecycle` (EOP-25),
  `trick-play` (EOP-70) and `game-over` (EOP-82) are all now permanently `true`. Each flip promised
  removal "once the feature is confirmed stable"; the promise has been made three times and kept
  zero times. ADR-013's Decision bullet "**A flag is deleted once its feature is released.**" and
  `feature-flags.md`'s "one release after full rollout" are, as practice, currently false. Prose
  commitments are not a lifecycle mechanism, and a fourth undated promise here would be worth
  exactly what the first three were.
- **Concrete expiry condition for `game-over`, replacing the undated promise.** The flag, its four
  `@ConditionalOnProperty` guards, `GameOverControllerDisabledIntegrationTest`, the `game-over`
  line in `src/test/resources/application.properties` and the `game-over` entry in
  `src/test/resources/feature-flag-registry.yml` are to be deleted in the story that closes **EOP-83** (the
  OpenAPI leaderboard divergences), or by **2026-09-18** — one month from this decision —
  whichever comes first. If neither has happened by that date, this ADR must be amended with the
  reason. The date does not move silently.
- **Second-order trap on removal.** `GameOverControllerDisabledIntegrationTest` asserts the OFF
  position — controller bean absent, the three use-case beans absent, both routes 404 — via
  `@SpringBootTest(properties = "eop.features.game-over=false")`. Once the flag and its guards are
  deleted, that property becomes inert (an unrecognised property is not an error), the beans become
  unconditional, and the test would be asserting a condition the code can no longer reach. It must
  be **deleted in the same commit as the flag**, not left behind. A suite that stays green after
  flag removal without that test being touched is evidence the removal was incomplete, not evidence
  that it was safe.
- ADR-013's "no audit trail" weakness is sidestepped here, not fixed. It applies to environment
  overrides, and this change moves the source default instead. Anyone who sets
  `EOP_FEATURES_GAME_OVER=false` on a deployed container still leaves no trace beyond their own
  shell history.
- The two OpenAPI divergences on this surface are **not** addressed by this ADR and are ticketed as
  EOP-83: `docs/api/openapi.yml` describes the leaderboard as available in any session state, where
  `GetLeaderboardUseCase` in fact refuses with 409 unless the session is `COMPLETED` and that 409
  is undocumented; and the spec states a 68-card deal where the runtime deals 66. Recording the
  flag position does not make the contract accurate.

  **Superseded in part, 2026-08-18 (EOP-92) — divergence 1 is closed, and closed in favour of the
  spec.** The clause above is no longer true of the code: `Hands.deal` deals all 68 cards again, so
  the runtime and `docs/api/openapi.yml` now agree and the deal is no longer a divergence at all.
  EOP-92 resolved it by changing the *implementation*, because the spec had documented the full deal
  correctly throughout — the code was what drifted, during the EOP-72 equal-hands period. See the
  EOP-92 amendment to [ADR-023](ADR-023-deal-remainder-and-turn-order.md). **Only the leaderboard
  divergence remains open under EOP-83**: the spec still describes the leaderboard as available in
  any session state where `GetLeaderboardUseCase` refuses with an undocumented 409 unless the session
  is `COMPLETED`. This note is left as an amendment rather than an edit so that the record of what
  EOP-83 originally covered survives, per the amend-don't-rewrite convention.
- `docs/architecture/building-blocks.md` is still absent project-wide (deferred to EOP-47), so the
  static module view of the now-live game-over surface exists only as Level 2 of
  `docs/architecture/C4-Diagrams.md`.

## Amendment, 2026-08-23 (EOP-84): the specified structural fix is now in place

The Consequences above name two defects in this record's own mitigation and specify the remedy for
both. Both are now built, in [ADR-053](ADR-053-feature-flag-registry-build-gate.md).

- **The hand-named key list is gone.** `FeatureFlagRegistryTest` derives the flag set from the keys
  under `eop.features` in the shipped `application.yml` *and* from every compiled
  `@ConditionalOnProperty` whose resolved key sits in that namespace, then requires both to equal the
  entries in `src/test/resources/feature-flag-registry.yml`. A fourth flag is no longer invisible: it
  fails the build until someone declares its intended state, its owning story and its expiry.
- **The `game-over` expiry is enforced rather than promised.** The 2026-09-18 date this record wrote
  in prose is now a field in the registry, and the build goes red the day after it passes. That is
  the only mechanism the three-flips-zero-removals bullet has ever had behind it.
- **The second-order trap on removal still needs a human.** Nothing detects that
  `GameOverControllerDisabledIntegrationTest` has been left asserting an unreachable condition after
  the flag is deleted. The expiry failure will make somebody look on 2026-09-19 at the latest, and
  the registry's own comment repeats the warning, but the check itself remains reviewer-enforced.
- **The masking trap is still there.** Flags remain pinned ON in test resources. ADR-053 routes
  around that rather than removing it, so the first bullet of this record's Consequences stands
  unchanged: any future guard in this area must read the shipped artefact, not the `Environment`.

## Relations

- **ADR-039** (new-game reset) — the slice this flag gates; amended with a pointer to this record.
- **ADR-013** (feature flags) — amended to catalogue `game-over` for the first time and to record
  the shipped-default masking trap.
- **ADR-040** (trick-play flag on) — the immediate precedent, and the structural template for this
  record; second of the three flips, this is the third.
- **ADR-008** (Liquibase migrations) — its EOP-27 amendment is the precedent for the distinction
  between a tripwire on a class and a hand-named assertion on a value.
- **ADR-015** (session token custody) — the unreissuable credential that the 404 ejection destroyed.
- **ADR-012** (two profiles only) — why `application-prod.yml` carrying no `eop.features` block
  makes the default-profile value authoritative under `SPRING_PROFILES_ACTIVE=prod`.
- **ADR-030** (scoring is derived, not accumulated) — the leaderboard re-derives scores rather than
  reading the persisted standings; that path is only now reachable.
- **ADR-032** (end-of-game transitions) — the transitions to `COMPLETED` that make a leaderboard
  read legal.
- **ADR-053** (feature-flag registry as a build gate) — implements the structural fix this record
  specified and deferred; deletes `ShippedFeatureFlagDefaultsTest` and encodes the `game-over`
  expiry date as an enforced field.
- **ADR-037** (front-end build-time feature flags) — the front-end half of the game-over surface is
  gated by `VITE_GAME_SCREEN_ENABLED`, a build-time variable, and is untouched by this server-side
  flip.
