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
- `context`: omit it. No changeset here carries one, and `spring.liquibase.contexts` is never set — and an unset context filter means *no filtering at all*, not "match nothing". A changeset tagged `context="prod"` therefore runs in **every** environment, exactly as if it were untagged. The tag is inert, which is worse than useless: it reads as a restriction to anyone reviewing the changelog while enforcing nothing. Verified against the resolved `liquibase-core` 5.0.3: `new ContextExpression("prod").matches(new Contexts())` returns `true`. Restricting a migration by environment takes the tag on the changeset *and* `spring.liquibase.contexts` set in **every** profile — above all in the profile that must **not** run it, because that is the one an unset value lets through. Setting it only in the profile that *should* run the migration achieves nothing: the other profile is still unset, still filters nothing, and still runs the changeset. The excluding profile must also name a real, **non-empty** context (for example `contexts: local`): an absent, empty, blank or `[]` value is discarded and behaves exactly as if the key had never been set, because Spring Boot skips `setContexts` entirely for an empty list and Liquibase drops whitespace-only tokens — so "set it in every profile" is only satisfied by a value that actually names something. Once `contexts` is set it becomes load-bearing in the opposite direction too, so a stale or misspelled context name then silently skips a changeset; a skipped changeset is silent for data, and silent for anything `ddl-auto: validate` does not check — it verifies tables and columns for *mapped entities only*, so a missing index, constraint or unmapped table sails past startup exactly as a missing row does. Note also that there is no `dev` and no `test` profile to name (see `configuration.md`)

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
5. **Prod data migrations** must be reversible. Do **not** reach for a bare `context="prod"` to keep one out of other environments: with `spring.liquibase.contexts` unset that tag is inert and the changeset runs everywhere anyway — see the `context` note under Changeset attributes for the mechanism and for what restricting by environment actually requires.
