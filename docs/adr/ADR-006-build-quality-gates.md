# ADR-006: Build Quality Gates

**Status:** Accepted
**Date:** 2026-07-26
**Deciders:** @team-member-tech-lead, @team-member-devops-engineer

## Context

The project needs automated code quality enforcement in CI to prevent style drift, latent bugs, and untested code from reaching `main`. Without gates, quality relies entirely on agent code-review discipline.

Multiple tools exist for each concern. The choice must balance thoroughness against build speed — a card game doesn't need enterprise-grade analysis but does need consistent baseline quality.

## Decision

- **Checkstyle** (custom ruleset — 4-space indent, consistent style) — fails on violations
- **SpotBugs** for bytecode-level bug detection — high/medium severity fails the build
- **JaCoCo** for code coverage — 80% instruction / 70% branch minimum
- **Maven Enforcer** — JDK >= 21, dependency convergence, common banned dependencies
- All gates run during `mvn verify` phase (not a separate step), keeping CI pipeline simple

Plugins pinned to specific versions for reproducible builds.

## Consequences

- **Positive:**
  - Consistent code style across all agent contributions
  - Automated detection of null-dereferences, unclosed resources, insecure deserialisation
  - Coverage minimum prevents untested code being merged
  - Dependency convergence catches conflicting transitive versions early
- **Negative:**
  - Adds ~15-20s to `mvn verify` (primarily SpotBugs analysis)
  - JaCoCo minimum may be frustrating during early rapid-prototyping phases of a new module
  - Google Java Style is not everyone's preference (120-char line limit, 2-space indent)
- **Mitigation:** JaCoCo rules exclude test scaffold classes. Style and coverage rules can be relaxed by future ADR if they become a bottleneck.

## Related

- [Build Quality Rules](../../.opencode/rules/build-quality.md)
- [Checkstyle](https://checkstyle.org/)
- [SpotBugs](https://spotbugs.github.io/)
- [JaCoCo](https://www.jacoco.org/jacoco/)
