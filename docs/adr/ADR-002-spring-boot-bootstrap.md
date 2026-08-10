# ADR-002: Spring Boot Walking Skeleton

**Status:** Accepted (framework version superseded 2026-07-27 — see Amendments)  
**Date:** 2026-07-26  
**Deciders:** @tech-lead, @architecture-guardian  

## Context
The project needed a deployable Walking Skeleton — a minimal end-to-end slice that compiles, passes tests, and runs as a verifiable service. The original `pom.xml` used only JUnit 5 with Java 26 but had no runtime framework and no CI pipeline.

The choice was between: (a) plain Java with a custom HTTP server, (b) Spring Boot, or (c) a lighter framework like Micronaut or Quarkus.

## Decision
- **Spring Boot 3.4.4** as the application framework
- **Java 21 LTS** (Spring Boot's LTS baseline)
- **`spring-boot-starter-web`** for REST + embedded Tomcat
- **`spring-boot-starter-test`** (JUnit 5 + Mockito + Spring Test)
- **`GET /health`** endpoint returning `"OK"` for deploy verification
- **Maven Wrapper** (`./mvnw`) for reproducible builds without global Maven
- **GitHub Actions** (`.github/workflows/ci.yml`) running `mvn verify` on push/PR

## Consequences
- **Positive:**
  - Health endpoint gives CI a concrete verification target
  - Standard framework — familiar to Java contributors
  - Easy to add REST API for the card game later
  - Maven Wrapper eliminates "works on my machine" build issues
- **Negative:**
  - ~15MB artifact size (vs ~10MB for a lean framework)
  - ~2-3s startup time (negligible for a server, overkill for a CLI-only app)
  - Spring DI temptation — game logic in POJOs must stay decoupled
- **Mitigation:** All game domain entities/use cases remain plain Java under `org.maglez.eop.*`, with no Spring annotations. Spring only touches the delivery layer (`Main.java` and future controllers/services).

## Amendments

**2026-07-27 — framework version moved to Spring Boot 4.1.0.** The decision above is
left as written because 3.4.4 is what was actually chosen and built on 2026-07-26. The
`pom.xml` parent was then raised to 3.5.16 and, the same day, to 4.1.0. Nothing else in
this ADR changed: Java 21, the starters, `GET /health`, the Maven Wrapper and the CI
workflow all still hold. **`pom.xml` is the authority on the current version** — treat
any version number quoted in prose as potentially stale.

## Related
- [Local Development Guide](../devops/local-development.md)
- [CI/CD Pipeline](../devops/ci-cd-pipeline.md)
