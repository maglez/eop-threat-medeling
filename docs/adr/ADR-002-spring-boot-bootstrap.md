# ADR-002: Spring Boot Walking Skeleton

**Status:** Accepted  
**Date:** 2026-07-26  
**Deciders:** @team-member-tech-lead, @team-member-architecture-guardian  

## Context
The project needed a deployable Walking Skeleton — a minimal end-to-end slice that compiles, passes tests, and runs as a verifiable service. The original `pom.xml` used only JUnit 5 with Java 26 but had no runtime framework and no CI pipeline.

The choice was between: (a) plain Java with a custom HTTP server, (b) Spring Boot, or (c) a lighter framework like Micronaut or Quarkus.

## Decision
- **Spring Boot 3.4.4** as the application framework
- **Java 21 LTS** (Spring Boot's LTS baseline) — compiles under JDK 26 but targets 21
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

## Related
- [Local Development Guide](../devops/local-development.md)
- [CI/CD Pipeline](../devops/ci-cd-pipeline.md)
