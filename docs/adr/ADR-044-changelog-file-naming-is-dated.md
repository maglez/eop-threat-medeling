# ADR-044: Liquibase changelog files are named by date, and the legacy `NNN-` sequence is frozen

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** @architecture-guardian, @tech-lead, @db-designer

## Context

`src/main/resources/db/changelog/changes/` holds nine changelog files under **two
different naming schemes**:

```
001-card-catalogue.xml  002-real-deck.xml  003-session-lifecycle.xml
004-trick-play-schema.xml  005-seat-and-sequence-bounds.xml  006-session-expiry.xml
2026-08-16--game-result.xml  2026-08-17--trim-deck-to-74-printed-cards.xml
2026-08-18--remove-ace-cards.xml
```

`.opencode/rules/database.md` prescribed the dated form and nothing else, while
six of the nine files on disk contradicted it. That much was a plain
documentation defect, and EOP-36 was raised to fix it. What the fix turned up is
the reason this needs an ADR rather than a wording change: **the two schemes are
not interchangeable, and picking the wrong one silently reorders migrations.**

### `<includeAll>` orders by filename, and nothing else

The master changelog registers migrations by directory scan:

```xml
<includeAll path="changes/" relativeToChangelogFile="true" endsWithFilter=".xml"/>
```

There is no manifest, no explicit `<include>` list and no ordering attribute, so
execution order **is** alphabetical filename order. Confirmed empirically rather
than assumed, by reading the changesets Liquibase reported while running
`DeckTrimMigrationRoundTripTest` (7 tests, green):

```
001-card-catalogue  002-real-deck  003-session-lifecycle  004-trick-play-schema
005-seat-and-sequence-bounds  006-session-expiry
2026-08-16--game-result  2026-08-17--trim-deck-to-74-printed-cards  2026-08-18--remove-ace-cards
```

Authorship order and execution order agree today. They agree **by accident**:
ASCII `0` sorts before `2`, so the `00N-` block happens to precede every dated
file. Nothing enforces that and nothing would report its loss.

### The hazard is a file nobody has written yet

Add `007-something.xml` — the obvious next step for anyone reading the six
legacy names as a live sequence — and it sorts **before** all three dated files.
Its DDL would run ahead of `2026-08-16--game-result.xml`, which is to say ahead of
schema it may depend on. The failure mode is not a rejected commit; it is a
migration that runs at the wrong point, on a fresh database, with a green build
on every environment that was already migrated past it.

A dated name cannot do this. `2026-…` sorts after `00N-` unconditionally, and
successive dates sort among themselves in authorship order, so the dated scheme
is the only one that keeps the two orders in step without a manifest.

### Renaming the legacy six is not available

The tidy fix — renumber the dated files to `007-`/`008-`/`009-`, or rename the
legacy six to dated equivalents — is closed off by Liquibase's identity rule.
A changeset is identified by `id + author + filepath`. Renaming a file mints a
new identity for changesets that have already executed, so every environment that
is not a fresh database sees the old rows orphaned in `DATABASECHANGELOG` and the
"new" changesets attempt to run again. That is Golden Rule 1 in
`.opencode/rules/database.md`, and it applies to the filename component just as
much as to the body.

So the split is permanent. The question is not how to remove it but which side of
it new work joins.

### A third, unticketed fiction in the same passage

While verifying the above, the rule file was found to quote the master changelog
as `<includeAll path="classpath:db/changelog/changes/"/>` — an absolute
`classpath:` form that the repository does not use and previously **silently
resolved to zero changesets**. That went unnoticed while the directory held only
a `.gitkeep`, because an empty changelog and an unreadable one are
indistinguishable until the first migration exists. `relativeToChangelogFile` and
`endsWithFilter` are both load-bearing — the latter because the `.gitkeep` itself
crashed startup once it was picked up — and the master changelog carries comments
saying so. An agent copying the rule file's snippet would have reintroduced a
no-op migration setup that reports success.

### Changeset `id` numbering: the rule was already right

EOP-36 was also asked to change the `id` bullet from per-file numbering to
global, on the evidence of `002-real-deck.xml` carrying `003-remove-placeholder-deck`
and `004-seed-real-deck`. Checking all nine files falsifies that: **eight of nine
restart at `001`**, and `002-real-deck.xml` is the sole outlier, continuing the
sequence from `001-card-catalogue.xml`. Rewriting the rule to say "global" would
have made it wrong for eight files in order to make it right for one — the same
defect class the story exists to remove, inverted. Golden Rule 1 also prevents
renumbering the outlier, so it stays as documented legacy.

Two `id` *styles* are in use and both work: a bare ordinal (`001`, the dated
files, author `miguel`) and an ordinal plus descriptive slug
(`001-create-card-table`, the legacy six, author `eop`). `006-session-expiry.xml`
shows the sanctioned way to express per-database variants of one logical change —
`001-add-expires-at-postgresql` beside `001-add-expires-at-h2`, sharing an ordinal
and distinguished by suffix.

## Decision

**New changelog files are named `YYYY-MM-DD--<description>.xml`, using the date of
authorship. The `NNN-` sequence is frozen at `006-` and must not be extended.**

- If two files share a date, append a discriminator: `-a`, `-b`.
- The legacy six keep their names permanently. They are not renamed, renumbered
  or migrated to the dated scheme, because their filenames are part of changeset
  identity.
- A new changeset is discovered by dropping the file into `changes/`. The master
  changelog is never edited to register one, and its `<includeAll>` attributes are
  not "simplified".
- `changeSet id` restarts at `001` in each file. `002-real-deck.xml` is a legacy
  outlier and is not a pattern to copy.

`.opencode/rules/database.md` keeps the operative directives — which scheme to
use, that `NNN-` is closed, and the one-line reason why — because an agent must be
able to act correctly without following a link. The ordering proof, the frozen-
identity argument and the `classpath:` history live here, per the placement lesson
of [ADR-043](ADR-043-liquibase-contexts-are-not-used.md): a rule file injected
into every agent's context every session pays for its length on every turn.

## Consequences

**Positive**

- Execution order and authorship order stay in step by construction, not by the
  ASCII coincidence they currently rely on.
- The `007-` hazard is closed before anyone hits it. It was latent, not
  hypothetical: the rule file's own tree diagram invited it.
- The rule file's `<includeAll>` snippet now matches the master changelog, so
  copying it produces a working setup rather than a silent no-op.
- The `id` bullet now describes the eight-of-nine practice and names the outlier,
  so neither reading of the codebase surprises the next author.

**Negative and residual risks — stated plainly**

- **Nothing enforces the naming scheme.** There is no test asserting that no new
  `00N-` file appears, and this ADR deliberately does not add one. The story that
  raised it carries an explicit warning that widening scope on this ticket family
  "buys a fourth round rather than a better repo", and the guard would be cheap
  but not free: it would have to encode the frozen legacy set as an allow-list and
  would go stale against it. So the `007-` hazard is closed by convention and
  review, not by the build. A reviewer seeing a new `NNN-` file should reject it.
- **Two schemes remain visible forever**, and the directory will always look
  inconsistent to a newcomer. That is the accepted price of Golden Rule 1. The
  alternative — renaming for tidiness — trades a cosmetic problem for checksum
  failures on every non-fresh environment.
- **The date is the date of authorship, not of merge**, so a long-lived branch can
  land a file that sorts before one already on `main`. Trunk-based development
  with short-lived branches keeps the window small, but it is a real window: the
  scheme guarantees ordering against the legacy block unconditionally, and
  ordering among dated files only to the extent that authorship order and merge
  order agree. Two migrations with a genuine dependency between them should not be
  in flight on separate branches.
- **The empirical ordering proof is a snapshot.** It was read from one Liquibase
  run against H2 on `liquibase-core` 5.0.3. `<includeAll>` ordering is documented
  behaviour rather than an accident of that version, but this ADR states what was
  observed rather than claiming the behaviour cannot change.

## Related

- [ADR-008](ADR-008-database-migration-liquibase.md) — Liquibase as the sole authority for schema changes
- [ADR-043](ADR-043-liquibase-contexts-are-not-used.md) — the sibling decision, and the placement precedent this ADR follows
- [ADR-006](ADR-006-build-quality-gates.md) — build quality gates, and why an unfireable guard is deleted rather than left passing
- `.opencode/rules/database.md` — the surviving directives
- `src/main/resources/db/changelog/db.changelog-master.xml` — the `<includeAll>` this decision rests on
- EOP-32 — the story that opened this defect class; EOP-36 — this decision
