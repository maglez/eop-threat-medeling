# ADR-008: Database Migration Strategy with Liquibase

- **Status:** Accepted (amended 2026-08-10 — the H2 console consequence was never true; amended 2026-08-19 — the "Context labels" comparison row records a capability this project has decided not to use, see ADR-043; amended 2026-08-23 — XML over formatted-SQL rationale, centred on rollback; all in Amendments)
- **Date:** 2026-07-26
- **Author:** Engineering Team
- **Deciders:** Architecture Guardian, DevOps Engineer, Security Auditor

## Context

The application will persist domain entities (threat cards, games, players, scores) requiring a relational database. Without a versioned migration strategy, schema changes become untraceable, environment drift occurs, and rollbacks are manual and error-prone. We need:

- **Traceability** — every schema change is versioned, reviewed, and auditable
- **Repeatability** — fresh environments reproduce the exact schema
- **Rollback support** — revert a migration cleanly if a deployment fails
- **CI integration** — migrations are validated in the pipeline before reaching production

## Decision

Adopt **Liquibase** with **XML changelogs** as the sole mechanism for all DDL and reference data DML changes.

### Why Liquibase over alternatives

| Criterion | Liquibase | Flyway |
|---|---|---|
| Changelog formats | XML, YAML, JSON, SQL | SQL only |
| Rollback support | First-class (`rollback` tags) | Limited (undo via SQL) |
| Preconditions | Rich precondition support | Basic version checks |
| Spring Boot integration | Auto-configuration via `liquibase-core` | Auto-configuration via `flyway-core` |
| Context labels | Built-in (`context` attribute) | Not available |
| Team preference | XML format preferred | — |

### Database targets

- **Dev / Test:** H2 in-memory (auto-configured via `application.yml`, zero setup)
- **Production:** PostgreSQL (via `application-prod.yml` and `DATASOURCE_*` env vars)

Hibernate `ddl-auto` is set to `validate` in all profiles — Liquibase owns schema management, Hibernate only validates that entities match the changelog.

## Consequences

### Positive

- Every schema change is a versioned file in `src/main/resources/db/changelog/changes/`
- Rollbacks are explicit and testable — each `<changeSet>` should include a `<rollback>` block
- CI can run `mvn liquibase:updateSQL` to preview migrations without applying them
- New developers get a fully migrated DB on `./mvnw spring-boot:run` with no manual steps
- H2 console available at `/h2-console` in `dev` profile for ad-hoc queries

*Retracted by the 2026-08-10 amendment below: there is no `dev` profile, and there is no
console. Ad-hoc inspection of the in-memory schema is covered in the amendment.*

### Negative

- Developers must write Liquibase XML changesets instead of raw DDL (learning curve)
- Changesets must never be modified after merge — corrections are always new changesets
- PostgreSQL dialect differences may surface in prod but not in H2 dev

### Mitigations

- Rule file `.opencode/rules/database.md` provides change-set templates and conventions
- H2 PostgreSQL compatibility mode can be used if dialect issues arise
- Staging environment runs against PostgreSQL to catch dialect mismatches before prod

## Amendments

**Amendment, 2026-08-10 (EOP-27).** The positive consequence "H2 console available at
`/h2-console` in `dev` profile for ad-hoc queries" is withdrawn. It was wrong in two
independent ways, and the second one mattered.

There has never been a `dev` profile. ADR-012 fixes the profile set at `{default, prod}`
precisely so that local and deployed runs execute identical configuration, so the console
was never scoped to a developer-only overlay — it was configured on the profile that every
run uses unless told otherwise. `application.yml` carried `spring.h2.console.enabled: true`
from the Walking Skeleton until this amendment.

That is a serious default to have written down. The console is unauthenticated arbitrary
SQL against the running application's own database, it accepts a JDBC URL supplied by the
caller — the CVE-2021-42392 JNDI/RCE shape — and this project has no Spring Security
dependency that could have stood in front of it.

It was never actually served. Spring Boot 4 moved the console's autoconfiguration out of
`spring-boot-autoconfigure` into a separate `org.springframework.boot:spring-boot-h2console`
module, which this project does not depend on, so no class on the classpath ever read the
property. The exposure was latent, not live: one transitive dependency, or one developer
adding the module for convenience, and it would have arrived with the configuration already
consenting and no review to notice.

The property therefore stays in `application.yml` as an explicit `false` with the reasoning
attached, rather than being deleted. Deleting it would leave the next person free to add the
module and inherit a default; leaving it means they have to change a line that says why it is
off. `H2ConsoleAbsentIntegrationTest` pins this: it asserts the console is absent, and its
load-bearing assertion is a tripwire on the autoconfiguration class itself, so the build fails
the day the module lands and the decision is taken at review time rather than in an incident.

Adding the module behind a working opt-in was considered and rejected as out of scope: it
would create exactly the attack surface this change removes, and it belongs to its own ticket
if anybody ever wants it. Until then, ad-hoc inspection of the in-memory schema is done with
`spring.jpa.show-sql=true`, `./mvnw liquibase:updateSQL` for the DDL, or an integration test —
an in-memory H2 database is only reachable from inside the JVM that owns it in any case, unless
an H2 TCP server is explicitly started, which nothing here does.

**Amendment, 2026-08-19 (EOP-35).** The "Context labels" row in the comparison table above
records a capability this project has since decided never to use. The row stands as written —
it was true at selection time and it remains true of Liquibase — but it is not a reason to
reach for `context="prod"`. A context attribute restricts nothing unless
`spring.liquibase.contexts` names a real, non-empty context in **every** profile, including the
one that must not run the changeset; left unset, as it is here, the tag is inert and the
changeset runs everywhere. `LiquibaseContextGatingAbsentTest` now fails the build if any
changeset in this repository carries `context`, `contextFilter` or `labels`, or if either
profile file sets the property. See
[ADR-043](ADR-043-liquibase-contexts-are-not-used.md) for the mechanism, the evidence and the
residual risks.

**Amendment, 2026-08-23 (EOP-162).** The comparison table row "Team preference | XML format
preferred | —" is replaced with a rationale.

Liquibase supports two changelog formats: XML and formatted SQL. The XML format was chosen
because it delivers three properties that formatted SQL cannot, all centring on rollback:

1. **Declarative, dialect-agnostic rollback.** Liquibase's XML elements express the inverse
   operation directly, and the rollback blocks in this repository use eight of them —
   `<dropTable>`, `<insert>`, `<delete>`, `<update>`, `<modifyDataType>`, `<dropColumn>`,
   `<dropUniqueConstraint>` and `<dropForeignKeyConstraint>`. Each renders for both H2 and
   PostgreSQL from a single source. Formatted SQL expresses rollback as a `--rollback` comment
   directive that the SQL parser must ignore — a mistyped directive silently yields no rollback
   and is discovered only during an incident. This repo writes every rollback explicitly
   (26 `<rollback>` blocks across ten changelogs), so it does not rely on Liquibase's
   auto-inference, but the explicit blocks still benefit from the declarative form: they are
   portable across the two database targets without writing dialect-specific SQL twice.

2. **Schema-validated rollback.** Every changelog carries `xsi:schemaLocation` to
   `dbchangelog-latest.xsd`, so a malformed XML rollback is rejected when the changelog is
   parsed — and because the test suite runs Liquibase, that rejection fails `./mvnw verify`
   rather than waiting for someone to attempt a rollback. A comment directive has no schema to
   be checked against, so the failure mode differs in *when* it surfaces, not merely in how
   loudly: XML makes a bad rollback a parse-time error, formatted SQL makes it a rollback-time
   one.

3. **Reference-data rollback.** The two migrations that delete seeded reference data — the
   deck trim of 2026-08-17 and the ace removal of 2026-08-18 — reverse a `<delete>` by
   re-seeding the exact rows, and their `<rollback>` blocks hold 10 `<insert>` elements between
   them (4 and 6 respectively) carrying 50 `<column>` children. That is the sharpest case for
   the declarative form: the same 50 column values written as `INSERT` statements in formatted
   SQL would have to be maintained per dialect, or pinned to the dialect intersection, and a
   down-migration that re-seeds reference data is exactly the kind that rots unnoticed because
   nothing exercises it until it is needed.

The worked example is changeset `006-session-expiry.xml`, which adds `expires_at` to
`game_session` with a database-side default. PostgreSQL uses `NOW() + INTERVAL '24 hours'`
while H2 uses `CURRENT_TIMESTAMP + INTERVAL '24' HOUR`. XML localises the divergence to the
`defaultValueComputed` attribute on each of two changesets, gated by `<preConditions
onFail="MARK_RAN"><dbms type="postgresql"/></preConditions>` and `<dbms type="h2"/>`. Formatted
SQL would require forking the file entirely.

The cost is real: five of the 26 rollbacks fall back to raw `<sql>` because no declarative
element covers the operation, all of them in `005-seat-and-sequence-bounds.xml` and
`006-session-expiry.xml`. XML confines dialect-specific text rather than eliminating it, on top
of the learning curve already conceded under **Negative** above. XML was the right choice for
this project's rollback requirements, and the row now records why.

## Related

- [ADR-002: Spring Boot Walking Skeleton](ADR-002-spring-boot-bootstrap.md)
- [ADR-007: Versioning Strategy](ADR-007-versioning-strategy.md)
- [ADR-043: Liquibase contexts are not used](ADR-043-liquibase-contexts-are-not-used.md)
- `.opencode/rules/database.md`
