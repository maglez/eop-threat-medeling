# ADR-006: Build Quality Gates

**Status:** Accepted
**Date:** 2026-07-26
**Deciders:** @tech-lead, @devops-engineer

## Context

The project needs automated code quality enforcement in CI to prevent style drift, latent bugs, and untested code from reaching `main`. Without gates, quality relies entirely on agent code-review discipline.

Multiple tools exist for each concern. The choice must balance thoroughness against build speed — a card game doesn't need enterprise-grade analysis but does need consistent baseline quality.

## Decision

- **Checkstyle** (custom ruleset — 4-space indent, consistent style) — fails on violations
- **SpotBugs** for bytecode-level bug detection — high/medium severity fails the build
- **JaCoCo** for code coverage — 80% instruction minimum, measured over the whole bundle with no exclusions
- **Maven Enforcer** — JDK >= 21, dependency convergence, common banned dependencies
- All gates run during `mvn verify` phase (not a separate step), keeping CI pipeline simple

Plugins pinned to specific versions for reproducible builds.

> **Closed gap — the branch-coverage limit is back (removed 2026-08-02, restored 2026-08-15).** A `BRANCH` limit at 70% was declared from the outset but could never fail: the codebase has zero branches, JaCoCo computes no ratio for an empty counter, and it therefore skips the limit silently. Worse, `org/maglez/Main.class` — the only class with behaviour — was listed in a JaCoCo `<excludes>` block, so the bundle consisted of one enum and reported 100% instruction coverage. Three documents cited that green tick as evidence of an 80/70 standard. Both the exclusion and the unfireable rule are now gone, and the 80% instruction minimum is enforced against a real measured **0.8571** (42 of 49 instructions). **The retirement condition — restore a `BRANCH` limit at 70% as soon as the first domain slice introduces branching logic, at which point the rule can actually fail — has now fired and been discharged.** EOP-15's second slice added `<jacoco.min.branch>0.70</jacoco.min.branch>` as a second `<limit>` inside the same `<element>BUNDLE</element>` rule that carries the instruction minimum, so both are measured over one bundle with no exclusions. It is a rule that can fail: the bundle now holds real branching, and it passes at a measured **0.9019** branch coverage (671 of 744 branches), re-derived from `target/site/jacoco/jacoco.csv` rather than read off a green tick — which is the mistake this note was written to record. See ADR-031.
>
> **The margin is two instructions.** At 49 instructions total, adding ~10 untested production instructions drops the ratio to 0.71 and fails the build. That is the gate doing its job — the first real feature must arrive with its tests — but do not mistake such a failure for a misconfiguration.

> **Amendment, 2026-08-18 (EOP-93): prose is now a build gate.**
>
> **Decision.** There is a fifth class of gate alongside Checkstyle, SpotBugs, JaCoCo and Enforcer: **documentation-integrity tests**, living in `src/test/java/org/maglez/eop/docs/`. They are plain JUnit tests with no Spring context, run by Surefire in the ordinary `verify` phase, and they read repository files as *text* — Surefire sets the working directory to the project base directory, so their relative `docs/` and `src/` paths resolve. There are three:
>
> | Test | Story | What it holds |
> |---|---|---|
> | `TrickPlayExceptionOriginTest` | EOP-14 | Derives the exception-origin count from the adapter and pins an ADR-023 paragraph to it |
> | `AdrIndexConsistencyTest` | EOP-32 | `docs/adr/README.md` against every ADR's status line — one row each, agreeing status word, amendment dates carried, declared column count |
> | `DeckArithmeticClaimsTest` | EOP-93 | Deck-arithmetic claims across all of `docs/**/*.md` and all of `src/**/*.java`, in Markdown prose and in javadoc alike |
>
> **What changed with EOP-93, and why this amendment exists.** The two earlier guards are each scoped to one artefact — one index file, one ADR paragraph. `DeckArithmeticClaimsTest` is the first that walks **the whole of `docs/` and the whole of `src/`**. Editing an arbitrary Markdown file anywhere under `docs/` can now turn the build red. That is a change to what `mvn verify` gates on, and this ADR is where such changes are recorded.
>
> **Consequences — positive.** EOP-92's review found three documents simultaneously stale while the build was green; EOP-75 left roughly nineteen sites stating a superseded deck size, four of them making an arithmetic claim that was outright false. Reviewer attention had already failed at this twice. On its first run `DeckArithmeticClaimsTest` found `GlobalExceptionHandlerTest.java:553`'s `new NoTamperingCardDealtException(78)` — a numeric literal two deck trims out of date that no prose search could have surfaced, and that two human review passes had missed.
>
> **Consequences — negative. These are the ones that must not be omitted:**
>
> - A Markdown edit anywhere under `docs/` can now fail the build. This is new, and it will surprise someone who thinks they are only writing prose.
> - **The matchers are phrase lists, not semantic analysis.** A green build is *not* proof that no stale or false claim exists — coverage is incomplete by construction. `EVERY_TABLE_SIZE` matches the nine phrasings that have actually gone stale in this repository; "at all four table sizes", "at each player count" and "the final trick is always short" all escape it. Do not cite a green build as evidence of documentation correctness.
> - Invariant B currently scans historical amendment blocks with no exemption. A *correctly historical* universal claim inside a dated block would fail the build and create pressure to rewrite history — the opposite of the convention that dated amendment blocks are immutable records. The fix, if it ever fires, is a region exemption for text under an `## Amendment` heading, not a loosening of the regex.
> - `ui/**` is outside invariant D. The front-end is where the 68 card images ADR-041 calls authoritative actually live, and EOP-69's trail shows `CardCatalogue.tsx` once carried a stale `DECK_SIZE` literal. It is clean today and unguarded tomorrow.
> - **Anti-vacuity rule.** Invariant B is meaningful only while some supported table size divides the deck evenly, and it asserts that precondition rather than skipping. If a future trim leaves no table size dividing evenly, the test must be **deleted**, not left passing — a guard that cannot fail is worse than no guard, which is the mistake the branch-coverage note above records.
>
> **Related:** [ADR-023](ADR-023-deal-remainder-and-turn-order.md) establishes reading a repository file from a test to hold documentation and code together as house practice; [ADR-041](ADR-041-printed-deck-has-no-aces.md) is the trim this guard defends; `.opencode/rules/build-quality.md` lists the gates and carries the same fifth entry.

> **Amendment, 2026-08-19 (EOP-35): configuration absence is now a build gate too.**
>
> **Decision.** There is a **sixth** class of gate: **configuration-absence tests**, which live beside the configuration they constrain rather than in `src/test/java/org/maglez/eop/docs/`. The first is `LiquibaseContextGatingAbsentTest` (`org.maglez.eop.config`), which fails `verify` if any changeset under `src/main/resources/db/changelog` carries a `context`, `contextFilter` or `labels` attribute, or if `application.yml` or `application-prod.yml` sets `spring.liquibase.contexts` or its `label-filter` sibling. See [ADR-043](ADR-043-liquibase-contexts-are-not-used.md) for why that absence is load-bearing.
>
> **Why it is not a documentation-integrity test.** It reads *shipped configuration* as text — XML under `src/main/resources/**` and the two profile YAML files — not prose under `docs/`. The count of three above stands unchanged, and the distinction is worth keeping: the fifth class carries the explicit warning that its matchers are phrase lists, and blurring the two would make that warning illegible. Its package placement follows the same reasoning — its peers are `ShippedFeatureFlagDefaultsTest`, `H2ConsoleAbsentIntegrationTest` and `ForwardHeadersStrategyPinnedIntegrationTest`, all of which assert something about configuration rather than about documents.
>
> **Admission test for the next one.** A configuration-absence gate is warranted only when the breach it prevents is **silent, fail-open, and invisible at startup** — the triad ADR-043 establishes for Liquibase context gating. Where a breach is loud, the breach is its own alarm and a guard is an accumulating tax. Do not add one for a property whose misconfiguration fails fast.
>
> **The fifth class's anti-vacuity rule applies here, with one correction.** This gate stays *fireable* for as long as Liquibase supports the attributes, so the deletion rule above is not expected to bite; the test's own javadoc pre-writes the protocol for the one case that would trigger it (genuinely wanting environment-restricted migrations — delete the test visibly and amend ADR-043 in the same commit, never weaken the assertions for one changeset). But EOP-35 also established that this class does **not** escape the phrase-list caveat: an XML attribute name is a spelling, and the guard's first revision matched `context=` and a non-existent `contexts=` while missing `contextFilter=`, which `liquibase-core` 5.0.3 reads *first* (`ChangeSet.java:432-434`). A configuration-absence matcher is a closed enumeration of the spellings the schema accepts, and must be revisited when the schema admits another.

## Consequences

- **Positive:**
  - Consistent code style across all agent contributions
  - Automated detection of null-dereferences, unclosed resources, insecure deserialisation
  - Coverage minimum prevents untested code being merged
  - Dependency convergence catches conflicting transitive versions early
- **Negative:**
  - Adds ~15-20s to `mvn verify` (primarily SpotBugs analysis)
  - JaCoCo minimum may be frustrating during early rapid-prototyping phases of a new module
  - The custom Checkstyle ruleset (`checkstyle.xml` — 4-space indent, 140-char line limit) is not everyone's preference, and it is not a published standard, so it has to be read rather than assumed
- **Mitigation:** JaCoCo measures every production class with no exclusions — a coverage number that excludes the code under test is worse than no number. Style and coverage rules can be relaxed by future ADR if they become a bottleneck.

## Related

- [Build Quality Rules](../../.opencode/rules/build-quality.md)
- [Checkstyle](https://checkstyle.org/)
- [SpotBugs](https://spotbugs.github.io/)
- [JaCoCo](https://www.jacoco.org/jacoco/)
