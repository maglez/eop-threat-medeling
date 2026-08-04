# Elevation of Privilege - EoP

A threat modeling card game based on the STRIDE framework (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege).

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Build | Maven Wrapper (`./mvnw`) |
| Tests | JUnit 5 |
| CI | GitHub Actions — `./mvnw verify`, then build, smoke test and publish the image to GHCR |
| Container | Multi-stage `Dockerfile`, `compose.app.yml` with PostgreSQL |
| Infrastructure | Terraform (`infra/`) — single EC2 instance, not yet applied ([ADR-012](docs/adr/ADR-012-deployment-target.md)) |
| AI Agents | OpenCode multi-agent system |

## Quick Start

```bash
# Build and test
./mvnw compile
./mvnw test

# Run the server
./mvnw spring-boot:run
curl http://localhost:8080/health
# → OK
```

## Project Structure

```
src/main/java/org/maglez/
├── Main.java                        # Spring Boot entry point + /health endpoint
└── eop/
    ├── entity/                      # Domain. Zero Spring, zero Jakarta imports.
    │   ├── Card.java                #   Immutable threat card
    │   ├── CardNotFoundException.java
    │   ├── Rank.java                #   Two through ace, ace high
    │   └── StrideCategory.java       #   The six suits; declaration order is load bearing
    ├── usecase/                     # Application. Ports and use cases, no framework.
    │   ├── CardRepository.java       #   Port, implemented outward
    │   ├── GetCardUseCase.java
    │   ├── ListCardsUseCase.java
    │   ├── PageQuery.java
    │   └── PageResult.java
    ├── adapter/
    │   ├── persistence/             # JPA lives here and nowhere else
    │   │   ├── CardJpaEntity.java
    │   │   ├── CardJpaRepository.java
    │   │   └── CardRepositoryAdapter.java
    │   └── web/                     # HTTP lives here and nowhere else
    │       ├── CardController.java
    │       ├── CardDto.java
    │       ├── GlobalExceptionHandler.java   # RFC 9457, one handler for the whole API
    │       └── PagedResponse.java
    └── config/
        └── UseCaseConfiguration.java # Bean definitions, so use cases stay framework-free

src/main/resources/db/changelog/      # Liquibase. Changesets are immutable once merged.
docs/api/openapi.yml                  # The API contract. Hand authored, ahead of the code.
```

The layering is not decoration. `entity` and `usecase` compile without Spring or
Jakarta on the classpath; everything framework-shaped is under `adapter` or
`config`. A dependency pointing the other way is a review failure, not a style
preference — see [`.opencode/rules/clean-architecture.md`](.opencode/rules/clean-architecture.md).

## Documentation

- [Product Requirements](docs/requirements/PRD-eop-card-game.md) — what the game is, and what it is not
- [Architecture Blueprint](.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md)
- [ADRs](docs/adr/README.md) — indexed, with implementation status
- [DevOps Guide](docs/devops/local-development.md)
- [CI/CD Pipeline](docs/devops/ci-cd-pipeline.md)

## Agent System

This project uses an OpenCode multi-agent team. See [AGENTS.md](AGENTS.md) for details and [Setup Guide](docs/devops/local-development.md) for prerequisites.
