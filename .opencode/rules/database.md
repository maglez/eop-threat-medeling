# Database Migration Conventions

## Authority

All DDL and reference data DML must go through **Liquibase changelogs**. Direct SQL against a database (including H2 console) is forbidden for schema changes. See ADR-008.

## Changelog Conventions

### File naming

```
src/main/resources/db/changelog/
  db.changelog-master.xml          # aggregator — <includeAll> only, never a changeset
  changes/
    YYYY-MM-DD--<description>.xml
```

Example: `2026-07-26--create-threat-card-table.xml`

The master changelog is `src/main/resources/db/changelog/db.changelog-master.xml`, referenced from `application.yml` as `spring.liquibase.change-log`. It uses `<includeAll path="classpath:db/changelog/changes/"/>`, so a new changeset is discovered by dropping the file into `changes/` — never edit the master to register one.

Use the date of authorship. If multiple changesets exist for the same date, append a discriminator (`-a`, `-b`).

### Changeset attributes

```xml
<changeSet id="001" author="miguel">
  <!-- your DDL here -->
  <rollback>
    <!-- reversible DDL -- DROP TABLE etc -->
  </rollback>
</changeSet>
```

- `id`: zero-padded sequence (`001`, `002`, …) within the file
- `author`: GitHub username or email
- `context`: optional — use `dev|test|prod` labels for environment-specific changes

### Preconditions

Add preconditions before destructive operations:

```xml
<preConditions onFail="MARK_RAN">
  <not>
    <tableExists tableName="threat_card"/>
  </not>
</preConditions>
```

Use `onFail="MARK_RAN"` for idempotent changelogs that may run against partially-migrated environments. Use `onFail="HALT"` for changes that must absolutely run.

### Rollback

Every changeset must include a `<rollback>` block (at minimum a comment acknowledging that rollback is not needed). For DDL changes the rollback is typically the inverse DDL.

## Golden Rules

1. **Never modify a merged changeset.** Liquibase identifies changesets by `id + author + filepath`. Modifying one after it has run on any environment will cause checksum failures. Always add a new changeset.
2. **One conceptual change per changeset.** Don't combine a new table with a data migration in the same changeset.
3. **`ddl-auto: validate`** — Hibernate must never auto-generate DDL. Liquibase is the sole source of truth.
4. **Test both forward and rollback.** Before pushing, run `mvn liquibase:rollback -Dliquibase.rollbackCount=1` against your local H2 to verify rollback works.
5. **Prod data migrations** must be reversible and include a `context="prod"` attribute.
