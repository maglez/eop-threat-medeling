# ADR-047: H2 is excluded from the deployable artifact, not moved to test scope

**Status:** Accepted (amended 2026-08-22 by EOP-38 — a second, independent
configuration-layer mechanism now prevents a deployed artifact serving the API schema,
so this ADR's `<excludes>` block is no longer the sole guard; see ADR-049)

**Date:** 2026-08-21

**Deciders:** @tech-lead, @security-auditor (raised the gap on EOP-27), @architecture-guardian

## Context

EOP-27 closed the H2 web console as an *autoconfiguration* concern. It pinned
`spring.h2.console.enabled: false` in `application.yml`, pinned it again in
`application-prod.yml` as a deliberately redundant second guard, and added
`H2ConsoleAbsentIntegrationTest` — whose load-bearing assertion is a tripwire that fails
the moment `org.springframework.boot:spring-boot-h2console` appears on the classpath, so
the decision to add the console module gets made at review time instead of being
discovered in an incident.

@security-auditor recorded one gap those three controls do not cover, explicitly *not* as
a condition of that merge. All three guard the path where Spring Boot registers the
console for you. None guards the path where somebody registers it by hand. The console is
unauthenticated arbitrary SQL against the running application's own database and it
accepts a JDBC URL of the caller's choosing, which is the shape of CVE-2021-42392, and
there is no Spring Security dependency in this project that could stand in front of it.

`com.h2database:h2` is declared `<scope>runtime</scope>`. Two facts about that were
established empirically before this decision was taken, and they point in opposite
directions.

**The dependency really does ship inside the artifact that runs on a server.** Packaging
`main` produced a 66,566,354-byte jar containing `BOOT-INF/lib/h2-2.4.240.jar`
(2,685,418 bytes), and that nested jar contains 13 classes under `org/h2/server/web`,
including `JakartaWebServlet`, `WebServlet` and `WebServer`. The auditor's count of 13 was
checked and is exactly right. So `H2ConsoleAutoConfiguration`'s `@ConditionalOnClass` gate
is already satisfied in production; only the autoconfiguration module is absent.

**But the ticket overstates how reachable that is today, and the correction matters more
than the original claim.** EOP-34 asserts a console is reachable "with zero dependency
changes" via either `new ServletRegistrationBean<>(new JakartaWebServlet(), "/h2-console")`
in a `@Configuration` class or `org.h2.tools.Server.createWebServer(...).start()`. Neither
compiles. `runtime` scope keeps a dependency off the *compile* classpath, and this was
settled with a real compile rather than by reading the scope table: a temporary source file
in `src/main/java` referencing `org.h2.tools.Server` failed with `package org.h2.tools does
not exist`. (`dependency:build-classpath -Dmdep.includeScope=compile` reports `h2-2.4.240.jar`
and is misleading — the plugin's `includeScope` does not mean "compile only". Do not use it
to answer this question.) The genuinely reachable paths are therefore reflection, or a
*second* reviewed change widening the scope to `compile` — not one careless commit. This
is future-proofing, and it should be described as such.

The other half of the context is what a naive fix would break. `application.yml` — the
default profile, which every `./mvnw spring-boot:run` uses — resolves
`spring.datasource.driver-class-name: org.h2.Driver` and
`spring.jpa.database-platform: org.hibernate.dialect.H2Dialect`. That path is not
incidental. It is documented in `README.md`, `SETUP.md`, `docs/devops/local-development.md`
and `AGENTS.md`; ADR-008 relies on it ("new developers get a fully migrated DB on
`./mvnw spring-boot:run` with no manual steps"); ADR-021 and ADR-035 both reason about it;
and ADR-012 says of the Swagger UI that it "remains available via `./mvnw spring-boot:run`
on the default profile. This is a decision, not a defect, and should not be 'fixed' later."

Set against that, no deployed configuration has any use for H2 at all. `compose.app.yml`
runs the application with `SPRING_PROFILES_ACTIVE=prod` against a real PostgreSQL service,
and the same file runs on EC2 with only environment variables differing. `application-prod.yml`
names `org.postgresql.Driver` and `PostgreSQLDialect`. And no document anywhere instructs
anyone to run the jar on the default profile: `grep -rn "java -jar" --include="*.md"`
returns nothing, and the sole tracked occurrence is in `.env.example`, describing
`java -jar -Dspring.profiles.active=prod`.

So the artifact needs no H2 and the developer workstation does.

## Decision

**Keep `com.h2database:h2` at `<scope>runtime</scope>`, and exclude it from the repackaged
jar** via an `<excludes>` block on the `spring-boot-maven-plugin`'s `repackage` execution.

This hardens the artifact rather than the scope declaration, which is what EOP-34's own
acceptance criterion actually asks for — Scenario 1 is written about the jar
(`JakartaWebServlet.class` "absent from the repackaged jar"), not about the word `test` in
`pom.xml`. The deployable artifact now carries no H2 at all; `./mvnw spring-boot:run` and
the whole test suite are untouched.

Three properties make this the right shape rather than a workaround.

It is **independent of scope**. If somebody later widens the dependency to `compile` for an
unrelated reason, the exclusion still holds and the classes still cannot reach the artifact.
A `test`-scope fix would silently unravel under the same edit.

It is **placed on the execution, not the plugin**, and that is the one real hazard of the
design. `excludes` is inherited from `AbstractDependencyFilterMojo`, which the plugin's
`run` goal also extends — so a plugin-level `<configuration>` would strip H2 from
`./mvnw spring-boot:run` and break local development in exactly the way this decision
exists to avoid. The `pom.xml` comment says "Keep it here; do not hoist it." This was not
reasoned about and assumed: after the change, `./mvnw spring-boot:run` on the default
profile was started and `/health` returned 200 with `Database dialect: H2Dialect` in the log.

It is **gated by a script in the required check**, `tools/artifact/assert-no-h2-in-jar.sh`,
run in the `build` job of `.github/workflows/ci.yml` immediately after `./mvnw verify` and
before the artifact is uploaded. No *surefire* test can do this job: surefire binds to the
`test` phase, which runs before `package`, so the repackaged jar does not exist when the
suite runs. That constraint is mechanical, and it is the only part of this that is forced —
see the rejected alternative below for why a failsafe integration test, which genuinely
could do it, was not chosen.
The script asserts there is exactly one candidate jar (refusing to guess otherwise), that
`BOOT-INF/lib/postgresql-*.jar` **is** present as a positive control so an empty or
truncated jar cannot pass an absence assertion for the wrong reason, that no
`BOOT-INF/lib/h2-*.jar` exists, and that no `org/h2/**` entry exists anywhere — the last
catching H2 arriving flattened, shaded, or under different coordinates.

Both directions were proven before this was committed. The hardened jar passes, and the
2,685,418-byte nested jar is gone from it. Deliberately not stated as an exact byte delta
between the two builds: the `pom.xml` is itself embedded in the artifact, so editing a
comment in it moves that delta by a few hundred bytes and any exact figure goes stale on
the next prose edit. The durable facts are the size of the jar removed and that
`BOOT-INF/lib/h2-*.jar` and every `org/h2/**` entry are absent. Restoring
`origin/main`'s `pom.xml` and repackaging makes the script exit 1 with
`FAIL: H2 is back inside the artifact: BOOT-INF/lib/h2-2.4.240.jar`, so the gate is not
vacuous. Pointed at a directory with no jar it also exits 1, so it cannot pass when there
is nothing to inspect.

**EOP-34's second item — a `maven-enforcer-plugin` ban on
`org.springframework.boot:spring-boot-h2console` — is declined.** The reasoning is the
auditor's own, from the EOP-27 review that raised this ticket: an enforcer ban "is a
stronger *prohibition* but a weaker *control*… it also cannot be satisfied. The first
developer who legitimately needs a console for a debugging session hits a hard wall with no
path through it, and the predictable outcome is `-Denforcer.skip=true`, or the ban being
deleted outright… A ban with no sanctioned escape hatch trains people to disable bans."
`H2ConsoleAbsentIntegrationTest`'s tripwire is the better control for the same risk,
because it is satisfiable: it fires at review time and a reviewer is its escape hatch. The
ticket itself warns that the ban may be redundant once the artifact is hardened, and that
"redundancy that costs a build plugin and an escape hatch to maintain is not automatically
defense in depth". It would also take the declared Maven plugin count from six to seven for
a prohibition that guards a module nothing has ever tried to add.

## Consequences

**Positive: the production artifact no longer contains a web console.** The 13
`org.h2.server.web` classes are gone from the jar the Dockerfile packages and the image that
runs on EC2. Reflection cannot reach a class that is not there, and neither can a future
scope widening.

**Positive: nothing about local development changed.** No new prerequisite, no PostgreSQL
container needed to run the application, `./mvnw spring-boot:run` still works from a fresh
clone, and ADR-012's deliberate default-profile path is left intact. The ≥16 H2-dependent
test classes under `src/test/java/org/maglez/eop/migration/` — several of which pin
H2-2.4.240 SQL states and exception class names — needed no change at all.

**Positive: the gate lives in the only required status check**, so it protects `main` rather
than being advisory. CI additionally boots the packaged artifact against real PostgreSQL in
the `image` job's smoke test on every pull request, which is independent evidence that
removing H2 did not break the thing that ships.

**Negative, and the accepted residual: a developer laptop still has H2 on its runtime
classpath.** `./mvnw spring-boot:run` resolves `runtime` scope, so a reflectively-registered
console remains possible there, against an in-memory database that holds nothing but that
developer's own scratch data. This decision gates the artifact, which is the part that
reaches a server. It does not and cannot gate a workstation, and the script's own header says
so explicitly under "What it does NOT check, and must never be described as checking".
Anyone citing this ADR as closing the hand-registration path *everywhere* is overstating it.

**Negative: `./mvnw spring-boot:run` and `java -jar` now have different classpaths.** That
is a divergence, and it is worth naming plainly because ADR-012's thesis is that local and
deployed behaviour should predict each other. The divergence is bounded to one dependency
that the `prod` profile never loads, and it is *visible* rather than silent — running the
jar on the default profile fails at startup with a missing driver, which is the correct
failure and not a subtle one. No document asks anyone to do that. But if a future change
gives the default profile a role in a deployed configuration, this ADR must be revisited
first.

**Negative: the gate is a shell script outside Maven**, so `./mvnw verify` alone does not
prove the property on a developer machine. The script is committed, executable and takes an
optional target directory, so it can be run locally — but it has to be run deliberately.

**A failsafe integration test was the alternative, and it would have worked.**
`maven-failsafe-plugin` binds `integration-test` and `verify`, both *after* `package`, and is
already resolvable at 3.5.6 from the Boot 4.1.0 parent, so an IT reading the jar with
`java.util.zip.ZipFile` would have gated this property from inside `./mvnw verify` with no new
dependency — closing exactly the negative stated above. This was established by
@code-reviewer during the gate round rather than at design time, and the first version of this
ADR and of the script header both overstated the constraint as "no test could do it". Corrected
here in the direction that makes the claim smaller. It was rejected on one ground: this project
declares no failsafe execution today, so configuring one would make a seventh declared Maven
plugin, and `tools/supply-chain/audit-plugins.sh` already establishes the
committed-script-invoked-from-CI idiom for a gate of exactly this shape. That is a consistency
and build-surface judgement, not a technical necessity, and it should be revisited — in favour
of the IT — if a failsafe execution is ever added to this build for another reason, or if the
locally-unexercised gate above ever actually lets a regression through.

**Negative: a lost `<excludes>` block is a silent regression until CI runs.** There is no
compile-time or test-time signal. The `pom.xml` comment, this ADR and the script's header all
point at each other for that reason, and the mutation test recorded above is what establishes
that CI would actually catch it.

**Neutral: the H2 console autoconfiguration module is still unbanned.** Adding it remains
possible, and remains loud — `H2ConsoleAbsentIntegrationTest` fails immediately. That test
must not be deleted; two of its four assertions are vacuous today by construction and are
kept precisely so that the day the tripwire fires, the assertions that the guard in
`application.yml` actually holds are already in place rather than needing to be remembered
under pressure.

**Correction recorded for the register.** Anywhere the hand-registration risk is described,
it should be described as requiring reflection or a second reviewed change, not as reachable
"with zero dependency changes". `runtime` scope was already doing more work than the ticket
credited it with.

## Related

- [ADR-008](ADR-008-database-migration-liquibase.md) — Liquibase owns all DDL; names the
  `./mvnw spring-boot:run` first-run path this decision preserves
- [ADR-012](ADR-012-deployment-target.md) — the container runs `SPRING_PROFILES_ACTIVE=prod`
  against PostgreSQL, and the default profile's local-only role is a decision rather than an
  accident
- [ADR-016](ADR-016-local-container-runtime.md) — Colima rather than Docker Desktop, for the
  container commands referenced here
- [ADR-017](ADR-017-frontend-delivery-topology.md) — the deployed topology in which the API is
  reachable only through Caddy
- EOP-27 — pinned `spring.h2.console.enabled: false` in both profiles and added
  `H2ConsoleAbsentIntegrationTest`; raised the gap this ADR closes
- EOP-34 — this ticket. Item 1 is delivered in substance (the artifact carries no H2) but
  deliberately not in the letter (the scope stays `runtime`); item 2 is declined above

> **Amendment, 2026-08-22 (EOP-38):** its `<excludes>` block was, until EOP-38, the sole
> mechanism preventing a deployed artifact from serving the API schema — a load it was
> never designed to carry and which its own text did not claim. A second, independent
> configuration-layer mechanism now exists: `springdoc.api-docs.enabled: false` and
> `springdoc.swagger-ui.enabled: false` sit in `application.yml`, the default profile's
> base, with a redundant pin in `application-prod.yml`. Its revisit trigger ("if a future
> change gives the default profile a role in a deployed configuration") is **not** tripped —
> the default profile gains no deployed role here; the change is purely about which layer
> carries the guard. The observation in the Consequences section — that running the jar on
> the default profile fails visibly with a missing driver — was independently reproduced
> during EOP-38, with the exact error: `java.lang.IllegalStateException: Cannot load driver
> class: org.h2.Driver` at `DataSourceProperties.findDriverClassName`, via
> `entityManagerFactory` → `liquibase` → `dataSource` → `HikariDataSource`.
