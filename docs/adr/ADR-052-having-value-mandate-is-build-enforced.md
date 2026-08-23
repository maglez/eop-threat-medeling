# ADR-052: The `@ConditionalOnProperty(havingValue = "true")` mandate from ADR-013 is now enforced by a build-failing bytecode test

**Status:** Accepted

**Date:** 2026-08-23

**Deciders:** @tech-lead, @architecture-guardian

## Context

ADR-013 (2026-08-14, EOP-48) mandated that every `@ConditionalOnProperty` in this repository carry `havingValue = "true"`. The mandate was violated twice after it was written:

1. **TrickController** — written with the loose form, fixed by commit `56c31b1` under EOP-14 Slice D
2. **SessionController** plus four unconditional use-case beans — the loose form paired with unconditional beans, fixed by commits `34d30d7` and `50850d1` under EOP-48

Both were caught by a reviewer, not by the build. Prose in an ADR demonstrably did not hold. The gap was not theoretical — it was the second violation that prompted EOP-50.

## Decision

A new test `src/test/java/org/maglez/eop/config/ConditionalOnPropertyHavingValueTest.java` reads compiled bytecode via Spring's `SimpleMetadataReaderFactory` + `PathMatchingResourcePatternResolver` (both from `spring-core`, already on the test classpath transitively — **zero new dependencies**). It enumerates every `@ConditionalOnProperty` site in `org.maglez.eop.**` and asserts each carries `havingValue = "true"`.

The mechanism was chosen after a spike that verified four facts:

1. **It sees every site, both placements.** The reader found exactly **6 class-level + 15 method-level = 21** sites, matching an independent grep exactly. Method-level annotations on `@Bean` methods are reachable via `AnnotationMetadata.getAnnotatedMethods(...)`.
2. **Annotation defaults are applied by the reader.** Attribute maps came back as e.g. `{havingValue=true, matchIfMissing=false, name=[eop.features.session-lifecycle], prefix=, value=[]}` — including attributes *not written in the source*. An **omitted** `havingValue` reads as the empty string `""`, which is `!= "true"`, so the violation is detected positively rather than by failing to find text.
3. **It is immune to source formatting**, because it reads `.class` files, not `.java` text. Multi-line annotations, unusual attribute order, comments, and line wrapping cannot fool it.
4. **Both attribute spellings in the codebase are read faithfully** — 5 sites use `prefix = "eop.features", name = "trick-play"`, 16 use a fully-qualified `name = "eop.features.trick-play"` with empty prefix. `value` is an alias for `name` in this annotation, so the check unions both.

All 21 existing sites already comply, so the check lands green. The gate is **preventive**, protecting against the third recurrence rather than fixing a present defect.

The check asserts on **every** `@ConditionalOnProperty` in `org.maglez.eop.**`, not only `eop.features.*` ones. This is deliberate: `.opencode/rules/caching.md` establishes that infrastructure toggles (e.g. a future `eop.cache.enabled`) default to `false` for the same fail-closed reason, and it "matters more here than elsewhere" for caching in front of security-sensitive reads. A future non-flag conditional is therefore correctly in scope. If a genuine need for a non-`"true"` `havingValue` ever arises, the resolution is to amend this ADR and add a narrowly-justified allow-list, not to weaken the assertion.

The check needs compiled classes, so it is a `test`-phase gate that depends on `compile` having run — normal for Surefire.

## Alternatives Considered

### ArchUnit

The conventional choice, semantically equivalent, better failure-message ergonomics out of the box. Rejected because it is a **new test dependency** for something `spring-core` already does, must keep `maven-enforcer-plugin`'s `DependencyConvergence` green, and adds supply-chain surface. The Maven layer is *not* covered by `tools/supply-chain/`, which audits the seven OpenCode npm plugins only.

This was a close call. ArchUnit remains the natural upgrade if a second, more complex architectural invariant ever needs enforcing — at which point the dependency amortises across several rules instead of one.

### A docs-style test parsing `.java` as text

In the manner of `AdrIndexConsistencyTest` / `DeckArithmeticClaimsTest`. Zero dependencies and a pattern the repo already trusts, but brittle against formatting exactly as the ticket predicted, and it would be a *phrase-list* gate — `.opencode/rules/build-quality.md` is explicit that such gates prove only "the specific phrasings that have gone stale here before are absent". Bytecode gives a semantic guarantee instead.

The new test lives in `config/`, not `docs/`, because it is **not** a prose gate — it asserts a property of compiled code.

### Checkstyle regex

Cheapest, already in the build. Rejected: `checkstyle.xml` currently has **no** `regexp` module family at all, so this introduces one from scratch; it cannot see annotation *defaults* (it would have to prove absence of text, the weaker formulation); and it has the worst failure messages of the four.

### Do nothing, rely on the rule-file line alone

The counter-argument is decisive: ADR-013 *already said so in prose*, and the mandate was still violated in **two independent commits**, each caught by a reviewer rather than the build. Prose demonstrably did not hold.

## The bean-repetition clause is not mechanically checkable

ADR-013's strengthened mandate has a second clause: a flag must be **repeated on every bean that opens or mutates the flagged state**, not on the controller alone; pure reads and collaborators shared with a second flag stay ungated with the reason in their javadoc.

This clause is **not mechanically checkable**, for three reasons:

1. **"Opens or mutates the flagged state" is a semantic judgement.** A checker cannot distinguish a use case that mutates flagged state from one that only reads it without understanding the domain. The distinction lives in the *intent* of the code, not in its type signature or annotation.

2. **The carve-outs are justified in javadoc prose.** The rule itself states that "collaborators that only read, and collaborators shared with another flag, stay ungated and say in their javadoc why" — javadoc is unparseable as intent by a build tool.

3. **A hand-maintained register recreates the drift-prone second register that EOP-48's security analysis rejected.** Naming every flag by name in the test would be equally brittle and would require the same maintenance burden the test is meant to eliminate.

The first clause — `havingValue = "true"` — is machine-enforced. The second clause remains reviewer-enforced. This asymmetry is stated openly rather than implied to be fully automated.

## Consequences

- **Positive:** The third violation will be caught by the build, not by a reviewer. The test is deterministic and immune to formatting changes.
- **Positive:** Zero new dependencies — uses `spring-core` classes already on the classpath.
- **Positive:** The test runs in the normal test phase, no special CI configuration needed.
- **Negative:** The test is in `src/test/java/org/maglez/eop/config/`, not in `src/test/java/org/maglez/eop/docs/`, because it reads bytecode and not prose. This is the correct location — it is a code property, not a documentation property.
- **Negative:** If a genuine need for a non-`"true"` `havingValue` ever arises, the ADR must be amended to add an allow-list. This is a deliberate friction point, not a gap.
- **Neutral:** The check covers infrastructure toggles (e.g. `eop.cache.enabled`) as well as feature flags. This is by design — see the Decision section.

## Related

- [ADR-013: Feature flags via Spring configuration properties](./ADR-013-feature-flags.md) — the mandate this ADR now enforces
- [ADR-006: Build quality gates](./ADR-006-build-quality-gates.md) — the build-quality gate posture this extends
- `.opencode/rules/feature-flags.md` — the rule file that references ADR-013
- `.opencode/rules/caching.md` — the precedent for fail-closed infrastructure toggles
- EOP-50 — the ticket