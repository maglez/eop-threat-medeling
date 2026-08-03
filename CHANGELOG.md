# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-SNAPSHOT] — Unreleased

### Added

Working code and tooling that exists in the repository today.

- Walking Skeleton: Spring Boot 4.1.0 + Java 21 + `GET /health` (ADR-002)
- Continuous integration: GitHub Actions running `./mvnw verify` on push and PR, uploading the built artifact, then building the container image, smoke testing it against a real PostgreSQL and publishing it to GHCR on `main`
- Container image: multi-stage `Dockerfile` producing a non-root JRE image, `compose.app.yml` running it alongside containerised PostgreSQL, published to `ghcr.io` by CI with no repository secrets (ADR-012)
- Deployment infrastructure: Terraform under `infra/` describing a single `t3.small` EC2 instance in a dedicated VPC with an Elastic IP and a separate encrypted EBS data volume (ADR-012). Validated with `terraform validate`; no `apply` has run yet because no AWS account has been provisioned
- Build quality gates: Checkstyle (`checkstyle.xml`), SpotBugs, JaCoCo 80% instruction coverage, Enforcer (ADR-006)
- Database migration wiring: Liquibase with a master XML changelog, H2 in dev, PostgreSQL in prod (ADR-008). No migrations have been written yet — `db/changelog/changes/` is empty
- Load testing: k6 with an InfluxDB + Grafana stack (Docker Compose), auto-provisioned dashboard, SLO thresholds (p95 < 200ms)
- GitHub MCP integration for agent-based PR and repository management, read-only at the server (ADR-003)
- Graphify knowledge graph exposed as a repo-local MCP server (ADR-011)
- Multi-agent system: 15 agents in `.opencode/agents/` — 11 delivery agents and 4 advisory experts
- Continuous flow over fixed sprints as the delivery model (ADR-010)
- `AGENTS.md` plus `.opencode/rules/` covering Clean Architecture, Security by Design and project conventions
- Product requirements: `docs/requirements/PRD-eop-card-game.md` defines the game, its scope, its domain concepts, nine open risks and six labelled assumptions, and is backed by epic EOP-5 with eight ordered stories
- Commit convention enforcement: `.githooks/commit-msg` requires `[EOP-NNN] <type>: <summary>`, activated per clone via `core.hooksPath`
- Tool governance: explicit deny-then-allow-lists for `github_*` and `graphify_*` in `.opencode/opencode.json`

### Decided, not yet implemented

Recorded here so that a decision is never mistaken for working code. Each of these
has an accepted ADR or rule file but no implementation in `src/` or `pom.xml`.

- API contract-first with OpenAPI 3.1 (ADR-004) — `springdoc` is on the classpath, but the hand-authored source-of-truth spec at `docs/api/openapi.yml` does not exist yet
- Error handling with RFC 9457 Problem Details (ADR-005) — no `@ControllerAdvice` or `ProblemDetail` handler exists
- Configuration management via `@ConfigurationProperties` + `@Validated` — rule only, no such class exists
- Resilience patterns: Resilience4j retry, circuit-breaker and time-limiter — `.opencode/rules/resilience.md` records the intent, but the dependency is absent from `pom.xml`
- Front-end stack: React + TypeScript + Vite + GOV.UK Design System CSS (ADR-009) — no `ui/` directory exists
- Automated deployment: the pipeline publishes a deployable image but does not deploy it. Rolling the new tag onto the instance is a manual pull-and-restart over SSH (ADR-012 records why CI-driven SSH deployment is deliberately avoided)

[1.0.0-SNAPSHOT]: https://github.com/maglez/eop-threat-medeling/releases/tag/v1.0.0-SNAPSHOT
