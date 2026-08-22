# ADR-049: Springdoc disabled by default — secure configuration is the base, not the overlay

**Status:** Accepted

**Date:** 2026-08-22

**Deciders:** @tech-lead, @security-auditor, @architecture-guardian

## Context

EOP-38 was raised by @security-auditor during the EOP-32 review. The problem: every piece of production hardening lived only in `src/main/resources/application-prod.yml`, so it applied only when `SPRING_PROFILES_ACTIVE=prod` was set. `application.yml` had **no `springdoc` block at all**, so the API schema and Swagger UI were served by default. That is fail-open-on-omission, in direct tension with `.opencode/rules/security.md`'s "Fail securely — default-denied access, explicit allow-lists". The safe configuration was the one you had to remember to ask for.

The ticket offered two candidate approaches:

- **Option A — invert the overlay.** Move hardening into `application.yml` so the base is secure; local dev opts into relaxations.
- **Option B — refuse to boot insecurely.** Keep the layout, add a startup assertion that fails fast when the default profile is active alongside evidence of a real deployment.

## Decision

**Option A, narrowly scoped to springdoc.** `springdoc.api-docs.enabled: false` and `springdoc.swagger-ui.enabled: false` now sit in `src/main/resources/application.yml` (the secure base). Local developers opt back in with two environment variables, `SPRINGDOC_APIDOCS_ENABLED=true` and `SPRINGDOC_SWAGGERUI_ENABLED=true`, added to `.env.example`. `application-prod.yml` keeps its existing springdoc block as a deliberately redundant second guard, with a comment modelled on the `spring.h2.console.enabled` comment already in that file ("Defense in depth, not a single gate").

### Measured evidence

All measurements taken on the **default profile** via `./mvnw spring-boot:run` (log line: `No active profile set, falling back to 1 default profile: "default"`).

**Before the change:**
- `GET /v3/api-docs` → **200**, body **18,739 bytes**, beginning `{"openapi":"3.1.0","info":{"title":"OpenAPI definition","version":"v0"},...`
- `GET /swagger-ui/index.html` → **200** (734 bytes)
- `GET /swagger-ui.html` → **302** redirect to `/swagger-ui/index.html`

**After the change, opt-in variables unset:**
- `/v3/api-docs` → **404**, body **103 bytes** (an RFC 9457 problem body, not a schema)
- `/swagger-ui/index.html` → **404**
- `/swagger-ui.html` → **404**
- `/health` → **200** (positive control, proving the app itself still works)

**After the change, with both opt-in variables set to `true`:**
- `/v3/api-docs` → **200**, body **18,739 bytes** — byte-for-byte the size measured before the change
- `/swagger-ui/index.html` → **200**
- `/swagger-ui.html` → **302**

This confirms Spring's relaxed binding maps `SPRINGDOC_APIDOCS_ENABLED` → `springdoc.api-docs.enabled` and `SPRINGDOC_SWAGGERUI_ENABLED` → `springdoc.swagger-ui.enabled`.

## Considered Alternatives

### Option B — refuse to boot insecurely

Rejected for five reasons:

1. **Its assertion would be dead code in the container.** The deployable jar cannot reach a served-Swagger state at all. Proven empirically: `java -jar target/ElevationOfPrivilegeEoP-1.0-SNAPSHOT.jar` on the default profile exits 1 with root cause `java.lang.IllegalStateException: Cannot load driver class: org.h2.Driver` at `DataSourceProperties.findDriverClassName`, via a chain `entityManagerFactory` → `liquibase` → `dataSource` → `HikariDataSource`. This happens because ADR-047 excludes H2 from the repackaged jar. An assertion guarding an unreachable state is maintenance with no live effect.

2. **It would need a "looks like a real deployment" heuristic** (non-loopback `server.address`, or a PostgreSQL URL) that must NOT fire on a developer machine, because local default-profile boot is explicitly blessed by ADR-012. That heuristic is guesswork and brittle.

3. **ADR-047's own governing precedent**: it declined an enforcer ban with the reasoning that "A ban with no sanctioned escape hatch trains people to disable bans" — controls must be *satisfiable*. Option A's escape hatch is one documented pair of env vars.

4. **`.opencode/rules/security.md` mandates two things Option A satisfies and B does not**: "Fail securely — default-denied access" (the unsafe state now requires an action, rather than the safe one), and "Defense in depth — multiple independent checks, not a single gate."

5. **Cheaper and stronger to test**: a plain in-suite `@SpringBootTest` pins it. No jar inspection, so no CI shell script and no seventh declared Maven plugin — it avoids ADR-047's gate-placement dilemma entirely.

### The ticket's claim about `server.error.*` was false

The ticket claimed that omitting the profile also yields "full stack traces in error responses". **That is false, and was measured to be false.** Every probe on the default profile returned a clean RFC 9457 body with exactly four keys — `detail`, `instance`, `status`, `title` — and no `trace`, no `exception`, no `errors` array. Specifically:

- `GET /api/v1/does-not-exist` → 404 `application/problem+json` `{"detail":"No static resource api/v1/does-not-exist.","instance":"/api/v1/does-not-exist","status":404,"title":"Not Found"}`
- `GET /api/v1/sessions/not-a-uuid` → 400 `{"detail":"Failed to convert 'sessionId' with value: 'not-a-uuid'",...}`
- `POST /api/v1/sessions` with body `{}` → 400 `{"detail":"Invalid request content.",...}` — no field-level binding-error leak
- `GET /error` directly (the endpoint `server.error.*` actually governs) → 500 `application/json` `{"timestamp":"2026-08-22T05:32:44.272Z","status":999,"error":"None"}` — no stacktrace, no message

Therefore `application-prod.yml`'s `server.error.include-exception: false` / `include-stacktrace: never` / `include-message: never` / `include-binding-errors: never` are **redundant pins of Spring Boot's own defaults** (Boot has defaulted these since 2.3), exactly like `server.forward-headers-strategy: none` is a redundant pin. They are not the source of any asymmetry. The project also bypasses `BasicErrorController` almost entirely via its RFC 9457 `@ControllerAdvice`, so `server.error.*` is largely inert in both profiles. **The real fail-open surface was springdoc alone — one surface, not three.**

### The single-mechanism fragility

Before EOP-38 there was exactly **one** mechanism standing between a deployed artifact and a served API schema: ADR-047's `<excludes>` block on the `spring-boot-maven-plugin`'s `repackage` execution. Two things make that a thin guarantee. Its **stated purpose is artifact hygiene, not profile safety** — nothing anywhere said that springdoc hardening depended on it. And per the comment at `pom.xml:130-135`, it silently stops working if that execution's id is renamed from `repackage`: Maven merges executions by id, so a rename produces a *second* execution while the inherited one still runs without the exclusion — H2 returns to the artifact **and the build stays green**. EOP-38 adds a genuinely independent second mechanism at the configuration layer.

Note: a *second* independent mechanism was initially hypothesised — that `spring.datasource.url: ${DB_URL}` would fail placeholder resolution because `compose.app.yml` has no `env_file:` and so the container never sees `DB_URL`. **That was disproven by experiment**: running the jar under `env -u DB_URL -u DB_USERNAME -u DB_PASSWORD -u DATASOURCE_URL -u DATASOURCE_USER -u DATASOURCE_PASSWORD` still failed on the driver class, never on placeholder resolution, because Boot's `DataSourceProperties.determineDriverClassName` runs before the URL is used. So there was one mechanism, not two.

## Consequences

**Positive: fail-closed by default.** The unsafe state now requires an explicit opt-in, satisfying `.opencode/rules/security.md`'s "Fail securely — default-denied access, explicit allow-lists".

**Positive: defense in depth.** Two independent mechanisms now guard the API schema: the configuration layer (this ADR) and the artifact layer (ADR-047). Neither depends on the other.

**Positive: testable in-suite.** `SpringdocDisabledByDefaultIntegrationTest` and `SpringdocOptInIntegrationTest` pin the property values and the HTTP responses without requiring jar inspection or CI shell scripts.

**Positive: local development still works.** Developers who copy `.env.example` get Swagger UI automatically via the two opt-in variables. The mechanism changed from implicit default to explicit opt-in; the outcome for the local developer is unchanged.

**Negative: a developer who never copies `.env.example` loses Swagger UI** until they do. This is a new thing that could in principle be set in a deployed environment — mitigated but not prevented by prod's second guard.

**Negative: the two `SPRINGDOC_*` variables are a new thing to document.** They are documented in `.env.example` and this ADR, but they did not exist before and a developer who does not read `.env.example` will not know to set them.

**Negative: the escape hatch is opt-in rather than opt-out.** A developer who wants Swagger UI must set two variables; the previous state required setting zero. This is the correct direction for security, but it is a friction increase.

**Neutral: the profile count is untouched — still exactly two**, default and `prod`. ADR-012's byte-identical-configuration rationale therefore survives intact, because that rationale is about not adding a third profile.

**Neutral: ADR-047's revisit trigger is NOT tripped.** It says "if a future change gives the default profile a role in a deployed configuration, this ADR must be revisited first." The default profile gains no deployed role here.

## Related

- [ADR-012](ADR-012-deployment-target.md) — references the Swagger UI availability; amended by this ADR
- [ADR-047](ADR-047-h2-excluded-from-deployable-artifact.md) — the artifact-layer mechanism; amended by this ADR
- [ADR-004](ADR-004-api-contract-first.md) — references springdoc; qualified by this ADR
- [ADR-005](ADR-005-error-handling-strategy.md) — references Swagger UI; qualified by this ADR
- [EOP-38](https://maglez.atlassian.net/browse/EOP-38) — the ticket
- `.opencode/rules/security.md` — "Fail securely — default-denied access, explicit allow-lists"