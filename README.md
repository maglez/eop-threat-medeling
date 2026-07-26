# Elevation of Privilege - EoP

A threat modeling card game based on the STRIDE framework (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege).

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.4 |
| Build | Maven Wrapper (`./mvnw`) |
| Tests | JUnit 5 |
| CI/CD | GitHub Actions |
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
- [ADRs](docs/adr/)
- [DevOps Guide](docs/devops/local-development.md)
- [CI/CD Pipeline](docs/devops/ci-cd-pipeline.md)

## Agent System

This project uses an OpenCode multi-agent team. See [AGENTS.md](AGENTS.md) for details and [Setup Guide](docs/devops/local-development.md) for prerequisites.
