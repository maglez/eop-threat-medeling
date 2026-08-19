# Database Migration Conventions

## Authority

All DDL and reference data DML must go through **Liquibase changelogs**. Direct SQL against a database (including H2 console) is forbidden for schema changes. See ADR-008.

## Changelog Conventions

### File naming

```
src/main/resources/db/changelog/
  db.changelog-master.xml            # aggregator — <includeAll> only, never a changeset
  changes/
    NNN-<description>.xml            # six legacy files — 001-…006-. Do NOT extend this sequence
    YYYY-MM-DD--<description>.xml    # the convention for every new file
```

**Two schemes are live in `changes/`, and only one of them governs new work.** Six files are `NNN-` prefixed (`001-card-catalogue.xml` … `006-session-expiry.xml`) and three are dated (`2026-08-16--game-result.xml`, `2026-08-17--trim-deck-to-74-printed-cards.xml`, `2026-08-18--remove-ace-cards.xml`). Name a new file with the **date of authorship** — `2026-08-16--game-result.xml` is the pattern. If two files share a date, append a discriminator (`-a`, `-b`).

**The `NNN-` sequence is frozen at `006-`. Never add a `007-`.** This is an ordering constraint, not a matter of taste: `<includeAll>` executes files in **alphabetical filename order**, so a `007-` file would run *before* every dated migration — ahead of schema its DDL may depend on. A dated name always sorts after the `00N-` block.The legacy six cannot be renamed to close the gap either, because a filename is part of changeset identity (Golden Rule 1), so the split is permanent. The observed execution order, the proof, and why renaming is closed off are in [ADR-044](../../docs/adr/ADR-044-changelog-file-naming-is-dated.md). Nothing in the build enforces the scheme — a reviewer does.

The master changelog is `src/main/resources/db/changelog/db.changelog-master.xml`, referenced from `application.yml` as `spring.liquibase.change-log`. It uses:

```xml
<includeAll path="changes/" relativeToChangelogFile="true" endsWithFilter=".xml"/>
```

so a new changeset is discovered by dropping the file into `changes/` — never edit the master to register one. Both attributes are load-bearing: do **not** "simplify" that to an absolute `path="classpath:db/changelog/changes/"`, which silently resolved to **zero** changesets here. The master changelog carries comments saying so, and ADR-044 records the history.

### Changeset attributes

```xml
<changeSet id="001" author="miguel">
  <!-- your DDL here -->
  <rollback>
    <!-- reversible DDL -- DROP TABLE etc -->
  </rollback>
</changeSet>
```

- `id`: zero-padded sequence (`001`, `002`, …) restarting at `001` in each file — the numbering is per-file, not global. Eight of the nine changelogs do this; `002-real-deck.xml` is a legacy outlier whose ids continue `003`/`004` from `001-card-catalogue.xml`, and Golden Rule 1 means it cannot be renumbered now. Do not copy it. Two id *styles* are in use and both are acceptable: a bare ordinal (`001`, as in the dated files) or an ordinal plus a descriptive slug (`001-create-card-table`, as in the legacy six). Where one logical change needs per-database variants, reuse the ordinal and distinguish by suffix — `006-session-expiry.xml` carries `001-add-expires-at-postgresql` alongside `001-add-expires-at-h2`
- `author`: GitHub username or email. Existing files use `eop` (the legacy six) and `miguel` (the dated three); it is part of changeset identity, so never edit it on a merged changeset
- `context`: omit it — and omit `labels` too, which is the same mechanism under a different name, so neither may be reached for as the other's replacement. `LiquibaseContextGatingAbsentTest` fails the build if a changeset in this repository carries either (or `contextFilter`, the spelling Liquibase itself prefers), or if either profile file sets `spring.liquibase.contexts` or its `label-filter` sibling. No changeset here carries one, and neither property is ever set. Do **not** reach for `context="prod"` to keep a migration out of another environment: an unset context filter means *no filtering at all*, not "match nothing", so the tag is inert and the changeset runs everywhere — worse than useless, because it reads as a restriction while enforcing nothing. Restricting a migration by environment takes the tag on the changeset *and* `spring.liquibase.contexts` naming a real, **non-empty** context (for example `contexts: local`) in **every** profile — above all the profile that must **not** run it, because an absent, empty, blank or `[]` value is discarded and behaves exactly as if the key had never been set. There is no `dev` and no `test` profile to name (see `configuration.md`). For the mechanism, the evidence and what setting it would cost, see [ADR-043](../../docs/adr/ADR-043-liquibase-contexts-are-not-used.md)

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
5. **Prod data migrations** must be reversible. Do **not** reach for a bare `context="prod"` to keep one out of other environments: with `spring.liquibase.contexts` unset that tag is inert and the changeset runs everywhere anyway — see the `context` entry under Changeset attributes for what restricting by environment actually requires, and [ADR-043](../../docs/adr/ADR-043-liquibase-contexts-are-not-used.md) for the mechanism and the evidence.
