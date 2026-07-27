# AGENTS.md

## Default Architecture: Clean Architecture

All code must follow Clean Architecture — dependencies point inward. Cross-boundary data uses DTOs, not entities. See `~/.agents/skills/clean-architecture/references/` for details.

Layers: Entities → Use Cases → Interface Adapters → Frameworks & Drivers

## Security by Design

Security is a first-class architectural constraint, not an afterthought. Apply:
- **Validate all inputs** at system boundaries — reject early, reject explicitly
- **Principle of least privilege** — grant minimal access for each operation
- **Fail securely** — default-denied access, explicit allow-lists
- **Defense in depth** — multiple independent checks, not a single gate
- **No secrets in code** — use env vars or config, never literals
- **Immutable domain entities** where possible — reduce attack surface from mutation

## Project Specifics

- **Java 21 LTS** / **Spring Boot 3.5.16** — single module, groupId `org.maglez`
- **Build:** `./mvnw` (Maven Wrapper — no global Maven install required)
- **Entrypoint:** `src/main/java/org/maglez/Main.java` — `@SpringBootApplication` with `GET /health`
- **Test framework:** JUnit 5 + Spring Boot Test — `src/test/resources/application.properties` overrides the datasource so tests run hermetically (no `DB_*` env vars needed)
- **Commands:**
  - `./mvnw test` — run all tests
  - `./mvnw compile` — fast compile check
  - `./mvnw spring-boot:run` — start application on port 8080
- Domain lives under `org.maglez.eop.*` — STRIDE categories, threat cards, privilege escalation rules
- Tests mirror source at `src/test/java/org/maglez/eop/`
- **Front-end:** React + TypeScript + Vite under `ui/`, GOV.UK Design System CSS
- **OpenCode plugins:** Graphify (knowledge graph), VibeGuard (secret redaction), DCP (context pruning), Supermemory (cross-session memory) — see Blueprint §11

## OpenCode Agent System

This project uses a multi-agent team defined in `.opencode/agents/`. Agents include:
- `@team-member-product-owner` — requirements discovery and backlog management
- `@team-member-tech-lead` — engineering orchestration and enforcement
- `@team-member-architecture-guardian` — C4 models and ADRs
- `@team-member-devops-engineer` — infrastructure and CI/CD
- `@team-member-db-designer` — database schemas and migrations
- `@team-member-ui-builder` — accessible front-end components
- `@team-member-tester-unit-and-quality` — unit test automation
- `@team-member-tester-api` — API integration tests
- `@team-member-security-auditor` — security and vulnerability audits
- `@team-member-code-reviewer` — static code review and SOLID compliance
- `@team-member-performance-engineer` — benchmarks and load testing