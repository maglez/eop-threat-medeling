# ADR-007: Versioning Strategy — Semantic Versioning

**Status:** Accepted
**Date:** 2026-07-26
**Deciders:** @tech-lead, @product-owner

## Context

The project needs a versioning strategy for releases. Without one, agents and consumers cannot distinguish breaking from non-breaking changes, and changelog entries lack a structured home.

## Decision

- **Semantic Versioning 2.0.0** — `MAJOR.MINOR.PATCH` format
  - **MAJOR** (1, 2, 3...): incompatible API or breaking domain changes
  - **MINOR** (0, 1, 2...): backwards-compatible feature additions
  - **PATCH** (0, 1, 2...): backwards-compatible bug fixes
- **Pre-release suffix** `-SNAPSHOT` during active development (e.g., `1.0.0-SNAPSHOT`)
- **`pom.xml`** `<version>` is the single source of truth
- **Git tags** mirror the version without suffix (`v1.0.0`, `v1.1.0`)
- **GitHub Releases** created from tags by the DevOps pipeline
- **`CHANGELOG.md`** at project root follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format, updated per release

## Consequences

- **Positive:**
  - Clear communication of breaking vs non-breaking changes
  - Machine-readable version for CI/CD and dependency consumers
  - Changelog provides a single place to track what shipped in each release
- **Negative:**
  - Requires discipline to bump version correctly — especially MAJOR on breaking changes
  - SNAPSHOTs in Maven can cause caching issues in CI
- **Mitigation:** Tech lead enforces version bump in code review. CI runs `mvn clean verify` to avoid SNAPSHOT cache problems.

## Related

- [CHANGELOG.md](../../CHANGELOG.md)
- [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
- [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
