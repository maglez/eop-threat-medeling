# ADR-053: The feature-flag registry is a build gate, and the flag set is derived rather than listed

**Status:** Accepted

**Date:** 2026-08-23

**Deciders:** @architecture-guardian, @tech-lead

## Context

EOP-82 shipped a finished feature dark for two stories. `eop.features.game-over` stayed `false` in
`src/main/resources/application.yml` while the slice behind it was complete, reviewed and merged;
the routes answered the framework's own 404 and nothing failed.

The reason nothing failed is structural, and it has not gone away. `.opencode/rules/feature-flags.md`
requires every flag to be pinned `true` in `src/test/resources/application.properties`, because a
suite running with a feature off would be testing its absence. Consequently the Spring `Environment`
in every `@SpringBootTest` resolves the test-resource override and never the shipped value. Any test
that asks the `Environment` what a flag is set to gets the answer the test resources gave it. The
shipped artefact is invisible to the suite.

EOP-27 met the equivalent trap with `spring.h2.console.enabled` and fixed it by removing the pin, so
that the `Environment` told the truth again (recorded in ADR-008's 2026-08-10 amendment). That fix is
unavailable here by construction: the pin is load-bearing for every other test in the suite.

ADR-042 therefore mitigated EOP-82 with `ShippedFeatureFlagDefaultsTest`, which read `application.yml`
off the classpath as a file and asserted three hand-named keys — and recorded in the same breath that
this was "an interim workaround, not the architectural answer". Its weakness was named precisely:
EOP-27's `H2ConsoleAbsentIntegrationTest` put its load-bearing assertion on the autoconfiguration
class itself, so the build fails the day the module lands, whatever anyone remembers to write.
A hand-named list is a snapshot of today's three positions. **A fourth flag was invisible to it**, so
the next feature to ship dark would have done so exactly as quietly as `game-over` did. It detected
the recurrence of one instance, not the class of fault.

ADR-042 also recorded a second problem that the same mechanism can address. Three flags have been
flipped on and none has ever been removed. Each flip promised removal "once the feature is confirmed
stable"; the promise has been made three times and kept zero times, which makes ADR-013's "a flag is
deleted once its feature is released" false as practice. ADR-042 concluded that prose commitments are
not a lifecycle mechanism, and gave `game-over` the project's first concrete expiry — deletion in the
story that closes EOP-83, or by 2026-09-18, whichever comes first — while noting that a fourth undated
promise "would be worth exactly what the first three were".

## Decision

### 1. The flag set is derived from the artefacts that exist, and held in three-way agreement

`src/test/java/org/maglez/eop/config/FeatureFlagRegistryTest.java` derives the set of feature flags
three independent ways and asserts that all three are equal:

| Source | How it is read | What it represents |
|---|---|---|
| `src/main/resources/application.yml` | `YamlPropertiesFactoryBean` on a `ClassPathResource`, filtered to keys starting `eop.features.` | the value that actually ships |
| `target/classes` | Spring's `SimpleMetadataReaderFactory` over every compiled `.class`, reading `@ConditionalOnProperty`, filtered to resolved keys starting `eop.features.` | the gate that actually exists |
| `src/test/resources/feature-flag-registry.yml` | SnakeYAML, already on the classpath through `spring-boot-starter` | the intent someone declared |

Each of the three pairings fails the build, and each catches a different fault:

- **A key in the shipped YAML with no registry entry** is an undeclared flag. Nothing records its
  intended state, its owner or its expiry, so no reader can distinguish a deliberate default from an
  oversight — which is the EOP-82 fault stated exactly.
- **An annotation whose key is in neither the YAML nor the registry** is the fail-closed trap. With no
  declared default the flag ships OFF, the routes answer a 404, and the suite notices nothing because
  the test-resource override pins the flag ON.
- **A registry entry with no YAML key and no annotation** is a stale or misspelled declaration: a flag
  that gates nothing, reading to a reviewer as protection that is not there.

This inverts the direction of the assertion. The old guard was silent unless someone remembered to add
a line; the new one fails *until* someone declares intent. That is what makes it a tripwire rather than
a snapshot, in EOP-27's sense.

### 2. The registry declares four fields, and all four are required on every entry

`src/test/resources/feature-flag-registry.yml`:

```yaml
flags:
  - key: eop.features.session-lifecycle
    shipped-default: true
    owner-story: EOP-25
    expiry: null
  - key: eop.features.trick-play
    shipped-default: true
    owner-story: EOP-70
    expiry: null
  - key: eop.features.game-over
    shipped-default: true
    owner-story: EOP-82
    expiry: "2026-09-18"
```

- `key` must start with `eop.features.` and name something after it. `caching.md` reserves `eop.*`
  directly for infrastructure toggles, and this registry does not govern those.
- `shipped-default` is **asserted against `application.yml`, not documented**. A disagreement fails the
  build. If the intent has genuinely changed, it changes here under review, so the decision is recorded
  rather than inferred from a value nobody chose.
- `owner-story` must match `EOP-\d+`. The reason a flag holds its current position is the first thing
  anyone deciding whether to remove it needs, and an owner that cannot be looked up defeats recording one.
- `expiry` is a **quoted** ISO date or the literal `null`.

The field set is checked in **both** directions — a surplus field fails as well as a missing one. That
is what catches a misspelling: `shipped-defualt` would otherwise read as an absent `shipped-default`
plus a harmless extra key, and `expriy` would silently mean "no expiry declared", which is the failure
mode this whole mechanism exists to remove.

### 3. `expiry` is `Optional<LocalDate>`, but the field is mandatory

`expiry: null` is a legitimate resting state meaning *no expiry has been authorised*. Omitting the key
is not, and fails the build.

The distinction is the point. A bare `LocalDate` would force inventing dates for `session-lifecycle`
and `trick-play` that no ADR authorised, and ADR-042 condemned exactly that kind of invention. But
letting the field be absent would let a new flag inherit no-expiry by silence, which is the habit
ADR-042 condemned from the other side. Requiring the field with an explicit `null` says "somebody
looked at this and decided" without fabricating a commitment.

The value must be quoted, because unquoted YAML resolves `2026-09-18` to a `java.util.Date` rather than
a string; the guard rejects that with the fix in the message rather than comparing a date nobody wrote.
Expiry is evaluated against `LocalDate.now(ZoneOffset.UTC)` so that the build's verdict does not depend
on which side of midnight the machine running it happens to be.

### 4. `ShippedFeatureFlagDefaultsTest` is deleted, and its rationale is preserved

The derived check subsumes it: same YAML-off-the-classpath technique, keys derived instead of listed,
shipped default asserted through the registry. Its class javadoc — why the `Environment` cannot be read
here, and why `H2ConsoleAbsentIntegrationTest` is right to read it — is carried into
`FeatureFlagRegistryTest`'s javadoc, adapted. That explanation is the most valuable thing the deleted
file contained: without it the next reader "simplifies" the classpath read into an `Environment` lookup
and reinstates EOP-82.

### 5. Three vacuity guards, because three sources can each silently yield nothing

Two empty sets compare equal, so each derivation is separately asserted non-empty, with a message
naming what to check: the expected three keys for the YAML derivation, `ConditionalOnPropertyHavingValueTest`
and its 21 sites for the bytecode derivation, and the registry path and top-level `flags` key for the
registry. An absent `target/classes` yields an empty file list rather than an exception, exactly as
`ConditionalOnPropertyHavingValueTest` does, so the vacuity guard reports the missing directory instead
of a stack trace from a walk over a path that does not exist.

This is not a theoretical precaution. The first implementation read the YAML through
`Properties.stringPropertyNames()`, which contractually admits a key only when *its value is also a
String*; Spring stores each parsed scalar as its own type, so every boolean flag was silently omitted
and the derivation returned empty while `getProperty` kept working. The agreement assertion compared an
empty set against the registry and reported the registry's own entries as missing. The vacuity guard is
what identified the cause.

### 6. Bytecode, not source text

For the reasons ADR-052 records: annotation formatting, attribute order, comments and line wrapping are
invisible to a metadata reader, and Spring applies the annotation's declared defaults on the way out, so
the scan proves what the container will see rather than what a regular expression happened to match. The
scanner handles both spellings in use here — `prefix = "eop.features", name = "game-over"` and the fully
dotted `name = "eop.features.game-over"` — and unions the `name` and `value` attributes, which are
declared aliases.

### 7. A machine-read registry is excepted from the "no flag catalogue" prohibition

`.opencode/rules/feature-flags.md` ends "there is no separate flag catalogue document", and that
prohibition stands for prose. It targets documentation that drifts silently, and its own sentence gives
the reason: "The flag namespace is a convention a reviewer enforces, not something the compiler checks."

A registry that gates the build is the opposite artefact. It cannot drift, because drift is precisely
what fails `./mvnw verify`. The rule is amended to record the exception rather than left to be
rediscovered as a contradiction.

ADR-019's rejection of a central `EopFeatures` class does not bear on this either. That rejected a
*production Java class imported by every gated component* — a coupling concern. A test resource is
imported by nothing and couples nothing. The registry is data, not a class.

## Consequences

- **The class of fault is now gated, not the instance.** A fourth flag cannot be added without a
  registry entry, and a flag cannot ship at a value nobody declared. All five acceptance criteria were
  demonstrated by deliberate mutation, each failing exactly one test and no others: a new YAML key with
  no entry; a key and entry with no annotation; a shipped value disagreeing with declared intent; an
  expiry moved into the past; and an omitted `expiry` field.
- **The flag lifecycle has a mechanism instead of a promise.** `game-over`'s 2026-09-18 expiry, which
  ADR-042 wrote in prose, is now enforced. When it passes the build goes red until someone removes the
  flag or extends the date under review.
- **Negative — an expiry turns the build red on a calendar boundary, and this project deploys every
  passing commit.** A red `main` blocks all deployment, including changes unrelated to the flag. This is
  accepted as the intended signal on the precedent of `tools/supply-chain/accepted-advisories.json`,
  which likewise fails in both directions deliberately. The mitigations are that the date is visible in
  the registry rather than hidden in a test, that it can be extended under review before it passes, and
  that only one flag carries a date at all. The residual risk is real: whoever hits it will hit it on a
  morning they had other plans.
- **Negative — the registry is still hand-editable, and this does not remove the need for review.** A
  developer determined to add a flag without thinking can add it to all three places in one commit. What
  the mechanism removes is the *silent* case: a flag existing without anyone having written down its
  intended state, its owner and its expiry. Three coordinated edits are visible to a reviewer in a way
  that a missing line in a hand-written test list was not.
- **Negative — `owner-story` and `shipped-default` are checked for shape and agreement, not for truth.**
  Nothing verifies that EOP-25 is a real Jira issue, or that it is the story that actually last moved
  `session-lifecycle`. A plausible-looking wrong value passes. This is the same bound the documentation
  gates in `src/test/java/org/maglez/eop/docs/` operate under, and worth stating for the same reason: a
  green build proves the registry is *consistent with the code*, never that its declarations are correct.
- **Negative — the masking trap itself is untouched.** Flags remain pinned ON in test resources and will
  keep masking the shipped default of every future flag from any test that reads the `Environment`. This
  ADR routes around the trap; it does not remove it, and EOP-27's fix remains unavailable. Any future
  guard in this area must read the shipped artefact, not the `Environment`.
- **Negative — the front-end's flags are out of scope.** ADR-037's build-time `VITE_*` flags are a
  different mechanism in a different toolchain, and this registry does not see them. A front-end feature
  can still ship dark exactly as `game-over` did.
- **The registry schema is a maintenance surface.** Changing it means changing the guard. The schema is
  deliberately four scalar fields for that reason; anything richer would be a parser to maintain.
- **`ShippedFeatureFlagDefaultsTest` no longer exists.** A search for it in ADR-042 or in commit history
  will find the interim guard this replaced.

## Related

- [ADR-013](ADR-013-feature-flags.md) — feature flags via Spring configuration properties; the flag
  catalogue this registry now enforces mechanically
- [ADR-042](ADR-042-game-over-flag-on.md) — specified this mechanism in its Consequences and deferred it
  to this story; source of `game-over`'s 2026-09-18 expiry
- [ADR-052](ADR-052-having-value-mandate-is-build-enforced.md) — the bytecode-scanning precedent
  (`ConditionalOnPropertyHavingValueTest`); orthogonal in subject, since it governs `havingValue` and
  `matchIfMissing` rather than which flags exist
- [ADR-008](ADR-008-database-migration-liquibase.md) — its 2026-08-10 (EOP-27) amendment is the
  tripwire-versus-snapshot precedent this decision is modelled on
- [ADR-019](ADR-019-session-lifecycle-and-join-codes.md) — rejected a central production flag class; does not
  govern a test-resource registry
- [ADR-012](ADR-012-deployment-target.md) — why the default profile's value is the one that ships under
  `SPRING_PROFILES_ACTIVE=prod`
- [ADR-037](ADR-037-frontend-build-time-feature-flags.md) — the front-end flag mechanism this registry
  does not cover
