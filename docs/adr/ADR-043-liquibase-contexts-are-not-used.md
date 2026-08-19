# ADR-043: Liquibase contexts are not used

**Status:** Accepted
**Date:** 2026-08-19
**Deciders:** @architecture-guardian, @tech-lead, @security-auditor

## Context

Liquibase changesets can carry a `context` attribute, and Spring Boot exposes a
matching `spring.liquibase.contexts` property. The obvious reading is that
tagging a changeset `context="prod"` keeps it out of every other environment.
That reading is wrong, and wrong in the dangerous direction: the tag is inert
unless the *runtime* also names a context, so a changeset that looks restricted
runs everywhere.

This ADR exists because the explanation had outgrown its home. The mechanism was
documented in `.opencode/rules/database.md` as a single 1858-byte bullet — 40% of
that rule file, 5.2x its next-longest line — sitting in a three-item lookup list
whose other entries are roughly 50 and 40 characters. Two things made that
placement wrong rather than merely untidy:

1. **Rule files are a hot-path artefact.** `.opencode/rules/*.md` is injected into
   every agent's context every session, so the cost is paid every turn while the
   payoff needs the reader to reach the end of the bullet.
2. **The passage pinned a library version.** It asserted a result "verified
   against the resolved `liquibase-core` 5.0.3" — a dated, falsifiable claim about
   a moving dependency, held in a file with no status, no date and no supersession
   mechanism. A rule bullet cannot go stale visibly; it just becomes quietly
   wrong. That is the exact failure class EOP-32 was raised to remove, so leaving
   version-pinned verification in a rule file re-created the disease in the cure.

### What was verified

Each YAML encoding of `spring.liquibase.contexts` was pushed through the real
production call path — no mocks, no reimplementation:

```
YAML document
  -> YamlPropertySourceLoader                     (spring-boot)
  -> Binder                                       (spring-boot)
  -> LiquibaseProperties.getContexts(): List<String>
  -> CollectionUtils.isEmpty(...) branch           (LiquibaseAutoConfiguration)
  -> SpringLiquibase.setContexts(String)           (called, or skipped)
  -> new Contexts(String)                          (liquibase-core)
  -> new ContextExpression("prod").matches(contexts)
```

The question asked of every row is the one that matters operationally: **with the
active profile being one that must *not* run the migration, does a
`context="prod"` changeset run anyway?**

### The 21-encoding matrix

`RUNS` means the `context="prod"` changeset executes despite the profile being
one that should exclude it — i.e. the restriction leaked.

| YAML encoding | Bound `List<String>` | `setContexts` called? | `SpringLiquibase.contexts` | Effective Liquibase store | `context="prod"` runs? |
|---|---|---|---|---|---|
| key absent | `null` | no | `null` | `[]` | **yes — LEAK** |
| `contexts:` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: ""` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: ''` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: []` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: {}` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: null` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: ~` | `[]` | no | `null` | `[]` | **yes — LEAK** |
| `contexts: " "` | `[""]` | yes | `""` | `[]` | **yes — LEAK** |
| `contexts: "   "` | `[""]` | yes | `""` | `[]` | **yes — LEAK** |
| list entry `- ""` | `[""]` | yes | `""` | `[]` | **yes — LEAK** |
| list entry `- " "` | `[" "]` | yes | `" "` | `[]` | **yes — LEAK** |
| `contexts: " , "` | `["", ""]` | yes | `","` | `[]` | **yes — LEAK** |
| `contexts: none` | `["none"]` | yes | `"none"` | `[none]` | no — safe |
| `contexts: NONE` | `["NONE"]` | yes | `"NONE"` | `[none]` | no — safe |
| `contexts: "!prod"` | `["!prod"]` | yes | `"!prod"` | `[!prod]` | no — safe |
| `contexts: local` | `["local"]` | yes | `"local"` | `[local]` | no — safe |
| list entry `- local` | `["local"]` | yes | `"local"` | `[local]` | no — safe |
| `contexts: no` | `["false"]` | yes | `"false"` | `[false]` | no — safe |
| `contexts: 0` | `["0"]` | yes | `"0"` | `[0]` | no — safe |
| `contexts: local,other` | `["local", "other"]` | yes | `"local,other"` | `[local, other]` | no — safe |

**13 leak, 8 suppress.** The leak set and the set of encodings whose *effective
store ends up empty* are the same set — the correlation is exact and total, and
that is the whole finding. An empty context store does not mean "match nothing";
it means "no filtering at all". The control confirms it directly:
`new ContextExpression("prod").matches(new Contexts())` returns `true`.

Two rows deserve to be read twice. `contexts: no` and `contexts: 0` are safe
**by accident**: SnakeYAML coerces `no` to the boolean `false` and Boot converts
it back to the *string* `"false"`, so Liquibase ends up filtering on a context
literally named `false`, which never matches `prod`. Config that reads as
"no contexts" happens to fail closed, for reasons that have nothing to do with
intent. Do not rely on it.

### Three discard points, not one

The 13 leaking rows do not all leak by the same route. Two of the three
mechanisms were confirmed by reading bytecode with `javap -p -c`; the third by
executing the split directly.

**1. Boot side — `setContexts` is never called (8 rows).** In
`LiquibaseAutoConfiguration$LiquibaseConfiguration`:

```
48: invokestatic  CollectionUtils.isEmpty:(Ljava/util/Collection;)Z
51: ifne          66
54: aload 6
56: aload_3
57: invokevirtual LiquibaseProperties.getContexts:()Ljava/util/List;
60: invokestatic  StringUtils.collectionToCommaDelimitedString:(Ljava/util/Collection;)Ljava/lang/String;
63: invokevirtual SpringLiquibase.setContexts:(Ljava/lang/String;)V
66: aload 6            <-- branch target: setContexts skipped entirely
```

An empty list jumps straight past the call, leaving `SpringLiquibase.contexts`
null — **byte-for-byte indistinguishable from the property being absent.** This
is why absent, empty, blank and `[]` are all equivalent to unset. A non-empty
list is joined with `collectionToCommaDelimitedString`.

**2. Liquibase side — `trimToNull` returns early (4 rows).** In
`Contexts.parseContextString(String)`:

```
0: aload_1
1: invokestatic  liquibase/util/StringUtil.trimToNull:(Ljava/lang/String;)Ljava/lang/String;
4: astore_1
5: aload_1
6: ifnonnull     10
9: return                <-- whitespace-only input: store left empty
```

So `setContexts` being called is not sufficient. `""` and `" "` reach Liquibase
and are discarded there. Note that Boot's `Binder` trims when splitting a scalar
into `List<String>`, which is how `contexts: "   "` becomes a *non-empty list
containing an empty string* — non-empty enough to pass mechanism 1, empty enough
to die at mechanism 2.

**3. Liquibase side — the comma split drops blank tokens (1 row).**
`contexts: " , "` survives both checks above: it binds to `["", ""]`, joins to
`","`, and `trimToNull(",")` returns `","`, which is not null. It still ends up
with an empty store, because `parseContextString` then calls
`StringUtil.splitAndTrim(",", ",")`, which returns `[]`:

```
10: aload_1
11: ldc           #60   // String ,
13: invokestatic  liquibase/util/StringUtil.splitAndTrim:(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;
```

`Contexts` also lower-cases every name as it is added to `contextStore`, which is
why `NONE` and `none` are the same context.

### Untagged changesets are unaffected

The same 21 rows were run against an untagged changeset —
`new ContextExpression().matches(contexts)` — and returned `true` in **all 21**,
unconditionally. Nothing currently in `src/main/resources/db/changelog/` carries
a `context`, so no value of this property, however malformed, can strand an
existing migration. The risk here is purely one of false confidence in a
restriction, never of a migration failing to run.

### The second fail-open direction: a context name that no longer matches

Everything above concerns a filter that names *nothing*. There is a second way to
fail open, and it applies only once someone has followed the instructions in this
ADR and actually set the property. Liquibase does not validate context names
against anything — there is no registry of legal contexts and no error for a name
that matches no changeset. So if `spring.liquibase.contexts` names `local` and a
changeset is tagged `context="locl"`, or a context name is renamed on one side of
the pair and not the other, Liquibase reports nothing at all: the changeset is
simply skipped, or is simply included, depending on which side went stale. The
mismatch is indistinguishable at startup from the correct configuration.

This is the counter-risk that made the original rule-file passage long, and it is
recorded here rather than there because it is unreachable while the property is
unset — which is the decision below. It becomes live the moment that decision is
reversed, so anyone reversing it should read this paragraph as part of the cost.

### `labelFilter` is the same trap under a different name

Labels are Liquibase's sibling gating mechanism, and they fail open in precisely
the same shape — worth stating explicitly, because the natural reaction to
learning that `contexts` cannot be trusted is to reach for `labels` instead and
land in the identical hole. `javap -p -c` on the same
`LiquibaseAutoConfiguration$LiquibaseConfiguration` method shows `labelFilter`
guarded by the same idiom at offsets 130–151:

```
130: invokevirtual LiquibaseProperties.getLabelFilter:()Ljava/util/List;
133: invokestatic  CollectionUtils.isEmpty:(Ljava/util/Collection;)Z
136: ifne          151
148: invokevirtual SpringLiquibase.setLabelFilter:(Ljava/lang/String;)V
151: aload 6
```

An empty or absent `labelFilter` skips the setter exactly as an empty `contexts`
does, and an unset label filter matches every changeset including labelled ones.
The decision below therefore covers labels as well as contexts: neither is used,
and neither may be reached for as the other's replacement.

### The attribute has two spellings, and `contextFilter` is the primary one

This was found by @architecture-guardian while reviewing EOP-35 and is recorded
here because it falsified the guard test's first revision. On a changeset, the
gating attribute has **two** legal spellings in `liquibase-core` 5.0.3, and the
one most people would write is the deprecated one:

| attribute | declarations in `dbchangelog-latest.xsd` | status |
|---|---|---|
| `contextFilter` | 6 | primary — read first |
| `context` | 6 | fallback, used only when `contextFilter` is empty |
| `contexts` (plural) | 0 | not a changeset attribute at all |
| `labels` | 5 | the only label spelling on a changeset |
| `labelFilter` | 0 | a *runtime* property name, not an attribute |

`dbchangelog-latest.xsd` is the schema `db.changelog-master.xml` names in its
`xsi:schemaLocation`, so it is authoritative for what our changesets may legally
carry. The precedence is explicit in `liquibase/changelog/ChangeSet.java:432-434`:

```java
this.contextFilter = new ContextExpression(node.getChildValue(null, "contextFilter", String.class));
if (this.contextFilter.isEmpty()) {
    contextFilter = new ContextExpression(node.getChildValue(null, "context", String.class));
}
```

The same precedence governs SQL visitors at `:506-508`; the serialised field list
at `:1496` names `contextFilter`; `:1532` accepts either. The field itself is
`private ContextExpression contextFilter` (`:155`). Labels have no such pair —
`:436` reads `labels` with no fallback.

Why this is recorded rather than merely fixed: the guard test's first revision
matched `\bcontexts?\s*=`, which covers `context=` **and a `contexts=` spelling
that the schema does not declare anywhere**, while missing `contextFilter=`
entirely because `Filter` intervenes before the `=`. It therefore spent its only
flexibility on a spelling that cannot occur and left the preferred spelling
unguarded — and `.opencode/rules/database.md` had already been changed to promise
agents that the build would catch it. The matcher is now
`\b(context|contexts|contextFilter)\s*=`, and injecting
`contextFilter="prod"` into `001-card-catalogue.xml` was confirmed to fail the
build before this was called done. The general lesson is in the amendment to
[ADR-006](ADR-006-build-quality-gates.md): an attribute matcher is a closed
enumeration of accepted spellings and must be revisited when the schema admits
another.

### Resolved versions at time of verification

Read from the project's own test classpath via
`./mvnw -o dependency:build-classpath`:

| Artifact | Version |
|---|---|
| `liquibase-core` | 5.0.3 |
| `spring-boot` | 4.1.0 |
| `spring-boot-liquibase` | 4.1.0 |
| `spring-boot-autoconfigure` | 4.1.0 |
| `spring-core` | 7.0.8 |
| `snakeyaml` | 2.6 |

Under Spring Boot 4 the relevant types are in
`org.springframework.boot.liquibase.autoconfigure` — not the Boot 3
`org.springframework.boot.autoconfigure.liquibase` — and `contexts` binds as
`List<String>`, not `String`. Both matter to anyone re-running this.

## Decision

**We do not use Liquibase contexts.** No changeset carries a `context`
attribute, and `spring.liquibase.contexts` is not set in any profile.

Restricting a migration by environment is therefore not something to reach for
casually. Doing it properly requires **both** halves, and the second half is the
one everybody forgets:

1. the `context` tag on the changeset, **and**
2. `spring.liquibase.contexts` naming a real, **non-empty** context in **every**
   profile — above all in the profile that must **not** run the changeset, since
   that is precisely where an absent value silently means "run everything".

Given that there are exactly two profiles (the default and `prod`, per ADR-012),
and no `dev` or `test` profile to name, the cost of setting this up correctly
exceeds any benefit we have identified. Environment-specific behaviour is
achieved with configuration properties and feature flags (ADR-013), not with
migration filtering.

`.opencode/rules/database.md` retains the operative directive — omit `context`,
and the non-empty requirement if anyone ever needs it — because an agent must be
able to act correctly without following a link. What it no longer carries is the
mechanism, the evidence or any library version. Those live here, where they can
be dated, amended and superseded.

## Consequences

**Positive**

- The rule file is back to being a set of directives an agent can act on. The
  `context` entry went from 1859 bytes to 1112, a 40% cut, and names no version,
  so it cannot silently rot. Re-measure with
  `awk 'NR==37' .opencode/rules/database.md | wc -c`.
- The verification is now falsifiable in the honest sense: it states the versions
  it holds for, so a dependency bump makes it *checkable* rather than quietly
  untrue. `docs/adr/README.md` and `AdrIndexConsistencyTest` make any future
  amendment visible.
- The three discard mechanisms are recorded with the bytecode that proves them,
  so the next person does not have to re-derive them from behaviour.

**Negative and residual risks — stated plainly**

- **`ddl-auto: validate` is not a backstop for this.** It was tempting to assume
  a mis-set `contexts` would be caught downstream by schema validation. It will
  not: `validate` walks the mapped Hibernate metamodel only, so a changeset that
  wrongly ran (or wrongly did not) is invisible to it unless it happens to touch
  a mapped column. That leaves "omit it" as the **sole** line of defence, which
  is exactly why the directive has to stay in the file agents are given rather
  than only in this ADR.
- **Enforcement is structural but not total.**
  `src/test/java/org/maglez/eop/config/LiquibaseContextGatingAbsentTest.java`
  turns the two halves of this decision into build failures: it asserts that no
  changeset under `src/main/resources/db/changelog/` carries a `context` or
  `labels` attribute, and that neither `application.yml` nor
  `application-prod.yml` sets `spring.liquibase.contexts` or its `label-filter`
  sibling. Both halves were verified to fire by mutation — injecting
  `contexts: ""` and adding `context="prod"` to a real changeset each turned the
  suite red, naming the offending `file:line`. A fifth assertion guards against
  the walk finding no files, so the first two cannot pass vacuously. What the
  test does **not** cover is the gap to keep in mind: it reads the two YAML
  files, so an environment override (`SPRING_LIQUIBASE_CONTEXTS=...` set on the
  container — the same env-var override mechanism [ADR-013](ADR-013-feature-flags.md)
  relies on for flags, though that ADR governs `eop.features.*` rather than
  arbitrary Spring properties) bypasses it entirely and
  would still be silent. It also only proves *absence* — it says nothing about
  whether a filter, once deliberately introduced, names the right context.
- **One row of the matrix above is invisible to the profile half of that test.**
  `contexts: {}` is an empty YAML *map*, and `YamlProcessor.buildFlattenedMap`
  recurses into it, so it contributes no flattened key at all rather than
  flattening to the empty string. The property assertion therefore cannot see it,
  even though the table above classes it as one of the thirteen leaking
  encodings. No choice of accessor fixes this — it is a property of the
  flattener, not of the test. Two things stop it mattering much: the
  changeset-attribute half is unaffected, so a `context="prod"` changeset still
  turns the build red no matter how the property was written; and this direction
  is only reachable at all by someone deliberately setting a property the
  decision says to leave alone. It is recorded here so the enumeration above is
  read as what it is — a control with a known blind spot — rather than as
  complete coverage of the matrix. The sibling encodings `contexts: no` and
  `contexts: 0`, which coerce to non-`String` values, *were* in this blind spot
  and no longer are: the test enumerates `keySet()` rather than
  `stringPropertyNames()` precisely so that a type coercion cannot hide a key.
- **A green build is not proof that the property is unset at runtime.** Following
  from the above: this decision is enforced at the source level only. The
  fail-open direction is unchanged, so an override that names nothing produces a
  clean startup and an unfiltered migration run.
- **This ADR pins versions, and dependencies move.** A Liquibase or Boot upgrade
  can invalidate the matrix. The mitigation is that this is now a dated document
  that can be amended, not that it cannot go out of date.
- **The mechanism is now one link away.** An agent reading only the rule file
  learns *what to do* but not *why*, which is an acceptable trade for a hot-path
  artefact, but it does mean the reasoning is no longer unavoidable.

## Related

- [ADR-008](ADR-008-database-migration-liquibase.md) — Liquibase as the sole authority for schema changes
- [ADR-012](ADR-012-deployment-target.md) — the deployment target and the two-profile arrangement, which is why there is no `dev` or `test` context to name
- [ADR-013](ADR-013-feature-flags.md) — feature flags as the supported way to vary behaviour per environment
- [ADR-006](ADR-006-build-quality-gates.md) — build quality gates, including the documentation-integrity tests
- `.opencode/rules/database.md` — the surviving directive
- `src/test/java/org/maglez/eop/config/LiquibaseContextGatingAbsentTest.java` — the guard that holds the source tree to this decision
- EOP-32 — the story that exposed the placement problem; EOP-35 — this move
