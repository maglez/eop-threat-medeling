# ADR-008: Database Migration Strategy with Liquibase

- **Status:** Accepted
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

### Negative

- Developers must write Liquibase XML changesets instead of raw DDL (learning curve)
- Changesets must never be modified after merge — corrections are always new changesets
- PostgreSQL dialect differences may surface in prod but not in H2 dev

### Mitigations

- Rule file `.opencode/rules/database.md` provides change-set templates and conventions
- H2 PostgreSQL compatibility mode can be used if dialect issues arise
- Staging environment runs against PostgreSQL to catch dialect mismatches before prod

## Related

- [ADR-002: Spring Boot Walking Skeleton](ADR-002-spring-boot-bootstrap.md)
- [ADR-007: Versioning Strategy](ADR-007-versioning-strategy.md)
- `.opencode/rules/database.md`
