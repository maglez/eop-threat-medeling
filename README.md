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
├── Main.java              # Spring Boot entry point + /health endpoint
└── eop/
    └── entity/
        └── StrideCategory.java   # STRIDE enum
src/test/java/org/maglez/
├── ApplicationContextTest.java   # Spring Boot context test
└── eop/entity/
    └── StrideCategoryTest.java   # Enum tests
```

## Documentation

- [Architecture Blueprint](.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md)
- [ADRs](docs/adr/README.md) — indexed, with implementation status
- [DevOps Guide](docs/devops/local-development.md)
- [CI/CD Pipeline](docs/devops/ci-cd-pipeline.md)

## Agent System

This project uses an OpenCode multi-agent team. See [AGENTS.md](AGENTS.md) for details and [Setup Guide](docs/devops/local-development.md) for prerequisites.
