# ADR-057: Honest Join-Code Rollback — Refusing to Truncate Live Data

**Status:** Accepted  
**Date:** 2026-08-23  
**Deciders:** @architecture-guardian, @tech-lead  

## Context

`2026-08-22--widen-join-code-to-8-characters.xml` (EOP-24, ADR-019) widened `game_session.join_code` from `VARCHAR(6)` to `VARCHAR(8)` and padded pre-existing six-character codes with `'00'`. Liquibase executes changesets in filename order and unwinds in reverse, so rolling back runs the changeset that removes the padding **first**, then the one that narrows the column.

Both rollbacks are unsafe, and they are unsafe **differently on the two engines**. This was measured, not reasoned.

### PostgreSQL 17 — silent data loss reported as success

Liquibase's PostgreSQL `modifyDataType` generator narrows with an **explicit cast**:

```sql
ALTER TABLE game_session ALTER COLUMN join_code TYPE varchar(6)::varchar
```

An explicit cast to a narrower `varchar` **truncates** in PostgreSQL rather than raising `value too long`. So rolling back rewrites every live eight-character join code to its first six characters, logs `Rollback command completed successfully.`, and reports no error. This is the primary defect, and it is strictly worse than a hard-failing behaviour.

### PostgreSQL 17 — a secondary, data-dependent hard failure

Two live sessions sharing a six-character prefix (`ABCDEFGH`, `ABCDEFJK`) both truncate to `ABCDEF` and collide on `uq_game_session_join_code`, aborting the rollback. That makes the abort a function of the data: a rollback rehearsed against an empty or lightly-seeded database succeeds, while the same rollback against production aborts. That is precisely the property a migration rollback must never have.

### H2 2.4.240 — a different failure, and not a safe one either

H2 hard-fails the narrowing (`Value too long for column "JOIN_CODE CHARACTER VARYING(6)"`, SQLSTATE 22001) instead of truncating, so the column-narrowing changeset is safe there. But the padding-removal changeset runs **first** and its `UPDATE game_session SET join_code = SUBSTRING(join_code, 1, 6) WHERE LENGTH(join_code) = 8 AND join_code LIKE '%00'` is a plain `UPDATE` that **succeeds on H2**, silently destroying a genuinely generated code ending `00`. Empirically confirmed by mutating the guard out of a scratch copy of the changelog: `ABCDEF00` was truncated and the rollback then reported clean — PostgreSQL's failure mode reproduced on H2.

### Why the fix is refusal rather than a better predicate

After the forward migration a padded code (`QRSTUV` becomes `QRSTUV00`) is **byte-for-byte indistinguishable** from a genuinely generated code that happens to end `00`. The padding destroyed the distinction and nothing in the schema records which rows it touched. Therefore **no predicate can separate them and no rollback can be lossless on populated data**. Tightening the padding-removal predicate is not a fix, and would in any case leave the PostgreSQL truncation of codes that do *not* end `00` completely untouched. The honest outcome is to refuse before anything is modified, and to state that recovery is a forward migration.

## Decision

### The mechanism: a guard changeset that runs first

The existing changesets are **immutable** — Liquibase identifies a changeset by `id + author + filepath`, so editing a merged one causes checksum failures (`.opencode/rules/database.md` Golden Rule 1). The fix therefore cannot touch the rollbacks that misbehave. The only available lever: `<includeAll>` orders `changes/` **alphabetically**, so a changelog dated later sorts later, executes later, and — because Liquibase unwinds in reverse — has its rollback run **first**, ahead of the two destructive ones.

New file `src/main/resources/db/changelog/changes/2026-08-23--guard-join-code-rollback.xml`, one changeset `001` by `miguel`. Forward change is `<empty/>` — a genuine no-op, confirmed by Liquibase logging `Empty change did nothing`. Its `<rollback>` holds an honest `<comment>` plus two statements:

```
ALTER TABLE game_session ADD CONSTRAINT ck_eop163_join_code_fits_varchar6 CHECK (LENGTH(join_code) <= 6)
ALTER TABLE game_session DROP CONSTRAINT ck_eop163_join_code_fits_varchar6
```

Adding a **validated** CHECK constraint fails if and only if a violating row exists. So a live wide code aborts the rollback with nothing modified; no wide code leaves the schema exactly as it was. It is portable ANSI SQL needing no `dbms` gating, it is immune to PostgreSQL constant-folding, and it works identically on both engines.

## Consequences

### Unwinding the widening now needs `rollbackCount=3`, not 2

The guard changeset sits between the widening and the two destructive rollbacks, so a full rollback must unwind all three.

### The guard is deliberately conservative

It refuses even the genuinely reversible case where every eight-character code is one the forward migration padded, because that case is not identifiable. The blast radius is bounded: padded sessions expire under the 24-hour `expires_at` TTL from `006-session-expiry.xml` (ADR-036), so the refusal becomes a no-op within a day of the forward migration.

### A latent, undetected hazard

The guard runs first only because its filename sorts last. **A future changelog dated before `2026-08-23` dropped into `changes/` would sort between the widening and the guard and silently defeat it, and nothing in the build detects that.** This is a genuine gap; it is stated honestly. A build gate to close it is out of scope for EOP-163.

### This is not a security control

It prevents data loss during an operational rollback. Do not describe it as a security boundary.

### Error message case sensitivity

Errors name the constraint, but the engines **fold case in opposite directions** — H2 reports `CK_EOP163_JOIN_CODE_FITS_VARCHAR6`, PostgreSQL reports it lower-cased — so every assertion on it is case-insensitive.

## Alternatives Considered

### `<preConditions onFail="HALT">` inside `<rollback>`

The direction the Jira ticket itself suggested, and it is **not expressible in Liquibase XML at all**. Verified against `dbchangelog-5.0.xsd` (Liquibase 5.0.3): `preConditions` is not one of the 48 members of the `changeSetChildren` group, so it is illegal inside `<rollback>`. A *changeSet-level* precondition is independently wrong because Liquibase evaluates it on forward `update` too, and changeset `002` deliberately creates the very eight-character codes such a precondition would forbid.

### `<stop>`

`liquibase.change.core.StopChange` throws **unconditionally** (`RuntimeStatement.generate()` throws `StopChangeException`), so it would break the two rollbacks that are legitimately safe: an empty database, and a database holding only legacy six-character codes.

### A PostgreSQL `DO $$ … RAISE EXCEPTION … $$` block

Gated with `<sql dbms="postgresql">` (following `006-session-expiry.xml`'s dbms-gating precedent) — gives the best error message but needs `splitStatements="false"`, has no H2 equivalent (H2 2.x has no anonymous block), and the portable substitute `SELECT CAST('message' AS INT) FROM game_session WHERE …` risks PostgreSQL folding the cast at plan time and raising even when zero rows match.

### A Liquibase `<customChange>` (a Java `CustomTaskChange`)

Legal in `<rollback>` and perfectly portable, but it makes the rollback depend on application classes being on the classpath, which is wrong for the exact scenario the guard exists to serve: a DBA unwinding with the Liquibase CLI or `mvn liquibase:rollback` (documented in `database.md` Golden Rule 4).

## References

- ADR-019 — Session lifecycle and join codes (the widening this guard protects)
- ADR-036 — Session expiry and sweep (the TTL that bounds the blast radius)
- ADR-056 — PostgreSQL migration tests via Testcontainers (the tests that exposed the defect)
- EOP-24 — Join code widening to eight characters
- EOP-163 — This story
- `src/main/resources/db/changelog/changes/2026-08-22--widen-join-code-to-8-characters.xml` — the changeset this guard protects
- `src/main/resources/db/changelog/changes/2026-08-23--guard-join-code-rollback.xml` — the guard itself
