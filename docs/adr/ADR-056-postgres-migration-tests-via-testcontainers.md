# ADR-056: Liquibase Migration Tests Against PostgreSQL 17 via Testcontainers

**Status:** Accepted  
**Date:** 2026-08-23  
**Deciders:** @architecture-guardian, @tech-lead  

## Context

Every automated test in this repository ran against H2 in-memory. Production runs PostgreSQL 17
(ADR-012, `application-prod.yml`). Liquibase is the sole source of schema truth (ADR-008) and
Hibernate runs with `ddl-auto: validate`, so the schema the application validates against is
whatever Liquibase rendered — on whichever engine the test happened to use.

That left three specific things unverified.

**A `<dbms>`-gated changeset pair where only one branch was ever executed.**
`006-session-expiry.xml` adds `game_session.expires_at` with a database-side default, and the two
engines spell the interval differently. Liquibase does not allow a `<dbms>` condition on
`defaultValueComputed`, so the changelog carries two changesets selected by
`<preConditions onFail="MARK_RAN"><dbms type="..."/></preConditions>`. The H2 changeset ran in
every CI build. The PostgreSQL changeset ran only in production, verified by nothing. A malformed
PostgreSQL default expression, a wrong column type, or a missing index on that branch would have
been discovered on deploy.

**Schema validation that only ever saw the H2 rendering.** `MappedSchemaValidationIntegrationTest`
proves Hibernate's mappings agree with the migrated schema, which is a real guard — but it agreed
with the *H2* schema. `GameSessionJpaEntity` maps the very column whose definition differs between
the two branches above.

**Rollbacks that had never run on PostgreSQL.** The existing round-trip tests
(`SessionExpiryMigrationTest`, `TrickPlaySchemaRoundTripTest` and their siblings) apply, roll back
and re-apply against H2. H2 cannot establish what PostgreSQL will do: PostgreSQL has transactional
DDL, takes different locks, rewrites tables on some type changes, and folds unquoted identifiers to
lower case rather than upper. A rollback rehearsed only on H2 is a rollback that has not been
rehearsed.

## Decision

### 1. Run the whole changelog against a real PostgreSQL 17 container

Three integration test classes live in `src/test/java/org/maglez/eop/migration/`:

- `PostgresChangelogIT` — applies `db.changelog-master.xml` to a container, asserts all 26
  changesets are recorded, asserts the PostgreSQL branch of `006-session-expiry.xml` is `EXECUTED`
  while the H2 branch is `MARK_RAN`, and asserts the rendered `expires_at` default, type and
  nullability from `information_schema`.
- `PostgresRollbackRoundTripIT` — applies, rolls back and re-applies on seeded data.
- `PostgresSchemaValidationIT` — the Spring half: starts a context against the container so
  Hibernate validates the mappings against the PostgreSQL-rendered schema, then selects from every
  entity in the JPA metamodel.

The image is `postgres:17-alpine`, pinned to match the `POSTGRES_IMAGE` default in
`compose.app.yml` so the tests and the deployed stack agree by construction.

### 2. Testcontainers 2.x, with the renamed module coordinates

`spring-boot-starter-parent:4.1.0` manages Testcontainers **2.0.5**. Every module was renamed in
the 2.x line: `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter` do not exist
at that version and fail resolution. The dependencies are
`org.testcontainers:testcontainers-postgresql` and
`org.testcontainers:testcontainers-junit-jupiter`, plus
`org.springframework.boot:spring-boot-testcontainers` for `@ServiceConnection` — all `test` scope,
all version-less so the parent's BOM owns the version.

The container class is imported from its new home, `org.testcontainers.postgresql`. The jar still
ships the 1.x `org.testcontainers.containers` package as a compatibility shim; writing against a
deprecated shim on day one is not worth the familiarity.

The PostgreSQL JDBC driver is **not** repeated as a test dependency. It is already declared at
`runtime` scope for production, and `runtime` is on the test classpath.

### 3. One container for the whole run, one database per test class

`PostgresTestContainer` is a package-private holder that starts a single container in a static
initialiser. Container startup is the dominant cost, so amortising it across all three classes
matters; `@Container static` on each class would start three.

Isolation is then bought at the database level: `freshDatabase(name)` runs
`DROP DATABASE IF EXISTS <name> WITH (FORCE)` followed by `CREATE DATABASE <name>` on an admin
connection. `WITH (FORCE)` is required because a failed run leaves connections behind, and neither
statement can run inside a transaction. The container's own default database is deliberately left
alone for `PostgresSchemaValidationIT`, because `@ServiceConnection` derives its JDBC URL from the
container and cannot be pointed at a database of our choosing.

Cleanup is Ryuk's job plus JVM exit. Ryuk stays enabled.

### 4. The integration suite is separated by class naming, which needs a seventh declared plugin

`./mvnw test` must stay H2-only, hermetic and fast — it must not require a Docker daemon. That is
not something surefire can express: `./mvnw test` and `./mvnw verify` both execute the `test`
phase with identical surefire configuration, so no JUnit tag and no `junit-platform.properties`
setting can make one run more tests than the other. Only a plugin bound to a later phase separates
them.

So `pom.xml` declares `maven-failsafe-plugin` — the **seventh** declared plugin, a cost ADR-047
weighed and declined for a different purpose. The declaration is deliberately bare: group id and
artifact id only, with no version, no configuration and no executions of our own. The parent's
`pluginManagement` supplies both the version and the unnamed execution carrying the
`integration-test` and `verify` goals.

**Do not delete that declaration as redundant.** During this story it was deleted, on the strength
of a `help:effective-pom` reading that appeared to show the Boot parent binding failsafe on its
own. It does not. A `pluginManagement` entry materialises into `build/plugins` only when the plugin
is also declared there, so the effective POM had been computed with the declaration in place and
the reading was circular. With the declaration gone, `./mvnw clean verify` ran the unit suite,
skipped all twelve integration tests, and reported BUILD SUCCESS with every coverage check met. A
green build that silently drops the entire integration suite is the failure mode that declaration
prevents.

JaCoCo needs no second `prepare-agent`. Failsafe's `argLine` also defaults to `${argLine}`, and
`integration-test` precedes `verify`, so integration coverage merges into `target/jacoco.exec`
before `jacoco:check` reads it.

The corollary is that **class naming alone selects the suite, and nothing enforces it**. Surefire's
default includes (`Test*`, `*Test`, `*Tests`, `*TestCase`) leave `*IT` invisible; failsafe's are
`IT*`, `*IT`, `*ITCase`. Renaming one of these classes to `*Test` would silently move it into the
fast suite and point it at H2 — reopening the exact gap this ADR closes, with no test going red.
That is a review responsibility.

### 5. `@ServiceConnection`, not `@ActiveProfiles("prod")`

`PostgresSchemaValidationIT` wires the container in with a nested `@TestConfiguration` exposing the
shared container as a `@ServiceConnection` bean, and overrides only the dialect and driver class as
`@SpringBootTest` properties.

Activating the `prod` profile would have been the shortcut and is wrong twice over: tests activate
no profile at all in this project (ADR-012 and the configuration rules), and `application-prod.yml`
resolves `${DATASOURCE_URL}`, `${DATASOURCE_USER}` and `${DATASOURCE_PASSWORD}` from the
environment, so the test would depend on ambient variables and would additionally drag in the
springdoc and error-handling overrides that have nothing to do with schema validation.

### 6. Colima needs two environment variables, exported from `.envrc`

Testcontainers 2.0.5 does not read the docker CLI *context*. This project uses Colima and does not
install Docker Desktop (ADR-016), so `/var/run/docker.sock` does not exist on a developer machine
and `DOCKER_HOST` is unset — every integration test fails with "Could not find a valid Docker
environment".

Setting `DOCKER_HOST` alone is not enough. Testcontainers bind-mounts the socket into Ryuk using
the literal path it resolved, and that path is a macOS-side socket that does not exist inside the
Colima virtual machine; virtiofs cannot create it, so the daemon returns
`operation not supported` and every test fails in class initialisation instead.
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` names the in-virtual-machine path to mount instead.

`.envrc` exports both, guarded so it only fires when the Colima socket exists and `DOCKER_HOST` is
not already set. A developer on Docker Desktop or a non-default Colima profile is untouched, and
`.env` still wins. CI needs none of this: `ubuntu-latest` has a daemon on the conventional path.

Disabling Ryuk would also have cleared the mount error. It was rejected — that trades reliable
cleanup of stray containers for a workaround, and leaked containers on a developer machine are a
worse problem than an environment variable.

### 7. CI needs no workflow change

The `build` job already runs `./mvnw verify --batch-mode` and `ubuntu-latest` already provides a
Docker daemon, so the integration suite is picked up with no edit to `.github/workflows/ci.yml`.
This is a deliberate consequence of putting the separation in the build rather than in the
workflow: there is one command to run locally and in CI, and no chance of the workflow and the POM
disagreeing about which tests exist.

## Consequences

**The `006-session-expiry.xml` PostgreSQL branch is now executed by a test**, and its rendered
default, column type, nullability and index are asserted. The H2 branch keeps running in the fast
suite, so both are covered, each on the engine that selects it.

**Rollbacks are now rehearsed on the engine that will run them.** `PostgresRollbackRoundTripIT`
establishes that every changeset in the changelog has a working rollback on PostgreSQL 17: applying
all 26, rolling all 26 back, and re-applying leaves the schema intact.

**It immediately found a defect, and corrected the premise of the ticket that describes it.**
EOP-163 is titled around a "lossy and hard-failing" rollback in
`2026-08-22--widen-join-code-to-8-characters.xml`. On PostgreSQL the failure is worse and different
in kind. Liquibase's PostgreSQL `modifyDataType` generator narrows the column with an explicit
cast, and an explicit cast to a narrower `varchar` **truncates** in PostgreSQL rather than raising
`value too long`. So rolling that changelog back does not hard-fail on a live eight-character join
code — it silently discards the last two characters of **every** such code and reports
`Rollback command completed successfully`. A seated player's code stops resolving, with no error
anywhere. The hard failure does exist, but only as a second-order effect: two codes sharing their
first six characters collide on `uq_game_session_join_code`, which aborts the rollback. That makes
the failure data-dependent, so a rollback rehearsed on an empty database succeeds and the same
rollback against production may abort.

Those behaviours are now pinned by tests, each marked `DEFECT (EOP-163)` in its display name and
javadoc with a note that the assertions invert when the fix lands. Pinning a known defect is
preferable to leaving it undescribed: the test says what the system does today, and it will fail
the moment that changes.

**A green `./mvnw verify` now requires a Docker daemon.** `./mvnw test` does not, and remains the
fast inner loop. This is the real cost of the decision and it is accepted: a migration test that
does not run against the production engine is not evidence about production.

**CI runtime.** The `build` job's `./mvnw verify` step grows by roughly the container start plus the
twelve tests. Measured locally, `clean verify` moved from 56 s to 1 m 12 s. The CI figure is stated
in the pull request for this story, measured from the `build` job on `main` before the change and
on this branch after it.

**A consequence for ADR-047 worth recording rather than acting on.** `pom.xml` notes that asserting
H2 is absent from the repackaged jar could have been a failsafe integration test rather than a
shell script, and that it was rejected because declaring failsafe would have cost a seventh
plugin. ADR-047 did not close that door: it says the decision "should be revisited — in favour of
the IT — if a failsafe execution is ever added to this build for another reason". EOP-164 is exactly
that other reason, so the revisit has now happened and the alternative path exists. It is not taken
here: `tools/artifact/assert-no-h2-in-jar.sh` still works and still runs in CI, and nothing about
this story forces it to change. The seventh plugin remains a real cost — what changed is that a
future story wanting that IT no longer has to pay it, not that the constraint disappeared.

**2026-08-23 — EOP-163 has landed, and the DEFECT tests are inverted, not deleted.** The guard changeset `2026-08-23--guard-join-code-rollback.xml` runs first on rollback (alphabetically last on forward), adding a CHECK constraint `LENGTH(join_code) <= 6` that aborts the rollback if any violating row exists. The three `DEFECT (EOP-163)` tests in `PostgresRollbackRoundTripIT` are now inverted — they assert the guard **refuses** the rollback rather than asserting the truncation succeeds. Their new names are:
- `refusesToRollBackTheWideningWhenAGenuineEightCharacterCodeExists`
- `refusesToRollBackTheWideningBeforeTwoSessionsCanCollide`
- `refusesToRollBackTheWideningWhenAGenuineCodeEndingInZerosExists`

The middle one asserts the error message names the guard constraint and does **not** name `uq_game_session_join_code`, proving the refusal happens before the collision rather than the collision being what saved us. `roundTripsASixCharacterSessionWithoutLoss` is unchanged and still green, which is the proof the guard is data-conditional rather than a blanket refusal. A new `JoinCodeRollbackGuardTest` covers the same four scenarios against H2 in the fast suite.

The changeset total is now **27**, not the 26 this document's body states, because the guard changelog contributes one more changeset. Both `EXPECTED_CHANGESET_ROWS` constants — the one in `PostgresChangelogIT` and the one in `PostgresRollbackRoundTripIT` — were bumped from 26 to 27 accordingly. That constant cannot drift silently: `PostgresChangelogIT.changesetTotalMatchesTheChangelogFiles()` counts the `<changeSet>` elements in the changelog files and compares the sum to the constant in both directions, so adding a changeset without bumping the constant fails the build, and bumping the constant without adding a changeset fails it too. Read every 26 earlier in this document as the figure at the time of writing.

## Alternatives Considered

**Keep H2 only, and review PostgreSQL changesets by eye.** This was the status quo. It is what left
one branch of a two-branch changeset unexecuted for the whole life of the feature, and it cannot
establish rollback behaviour at all.

**A PostgreSQL service container in the CI workflow instead of Testcontainers.** Cheaper in CI, but
it only works in CI: a developer could not run the same test locally, and the image version would
live in the workflow rather than beside the code. Testcontainers gives one command that behaves the
same in both places.

**`@ActiveProfiles("prod")` for the Spring test.** Rejected in decision 5.

**A dedicated `test` profile or `application-test.yml` for PostgreSQL settings.** Rejected because
ADR-012 fixes the profile count at two and the suite deliberately overrides the default profile
through `src/test/resources/application.properties`. Two explicit `@SpringBootTest` properties are
narrower and local to the one class that needs them.

**Tagging the integration tests with JUnit tags instead of `*IT` naming.** Does not work, for the
reason in decision 4: both `test` and `verify` run the same surefire execution, so a tag cannot
distinguish them without configuring surefire — which would itself be an additional declared
plugin, and a more invasive one.

## References

- ADR-008 — Liquibase as the sole source of schema truth
- ADR-012 — two profiles only, container runs with `SPRING_PROFILES_ACTIVE=prod`
- ADR-016 — Colima as the container runtime
- ADR-047 — H2 excluded from the deployable artifact, and the seven-plugin cost
- EOP-163 — the join-code widening rollback defect, whose premise this story corrects
- EOP-165 — narrowing the schema-validation argument in ADR-008's 2026-08-23 amendment
