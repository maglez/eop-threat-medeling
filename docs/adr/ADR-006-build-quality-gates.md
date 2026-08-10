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

> **Known gap — there is no branch-coverage limit (removed 2026-08-02).** A `BRANCH` limit at 70% was declared from the outset but could never fail: the codebase has zero branches, JaCoCo computes no ratio for an empty counter, and it therefore skips the limit silently. Worse, `org/maglez/Main.class` — the only class with behaviour — was listed in a JaCoCo `<excludes>` block, so the bundle consisted of one enum and reported 100% instruction coverage. Three documents cited that green tick as evidence of an 80/70 standard. Both the exclusion and the unfireable rule are now gone, and the 80% instruction minimum is enforced against a real measured **0.8571** (42 of 49 instructions). **Retirement condition:** restore a `BRANCH` limit at 70% as soon as the first domain slice introduces branching logic, at which point the rule can actually fail.
>
> **The margin is two instructions.** At 49 instructions total, adding ~10 untested production instructions drops the ratio to 0.71 and fails the build. That is the gate doing its job — the first real feature must arrive with its tests — but do not mistake such a failure for a misconfiguration.

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
