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
- Database migrations: Liquibase with a master XML changelog, H2 in dev, PostgreSQL in prod (ADR-008). The first migration creates the `card` table and seeds the placeholder deck
- Card catalogue: `GET /api/v1/cards` and `GET /api/v1/cards/{cardId}` serve a six-card placeholder deck, one card per STRIDE category, as read-only reference data (EOP-6)
- API contract: the hand-authored OpenAPI 3.1 specification at `docs/api/openapi.yml` (ADR-004), written before the controllers that implement it
- Error handling: a single `@RestControllerAdvice` rendering RFC 9457 problem details, with a unit test for every mapped exception (ADR-005)
- Feature flags: decided as Spring configuration properties under `eop.features.*` (ADR-013). No flag exists yet — the first arrives with the first live deployment
- Real-time transport and player identity: decided from a time-boxed spike that ran a real server-sent-events endpoint against the application (EOP-8). Server-sent events carry state to every connected player (ADR-014); a server-issued opaque token in per-tab session storage identifies a player (ADR-015). Both are decisions only — the spike code was deleted and no production code graduated from it
- Load testing: k6 with an InfluxDB + Grafana stack (Docker Compose), auto-provisioned dashboard, SLO thresholds (p95 < 200ms)
- GitHub MCP integration for agent-based PR and repository management, read-only at the server (ADR-003)
- Graphify knowledge graph exposed as a repo-local MCP server (ADR-011)
- Multi-agent system: 15 agents in `.opencode/agents/` — 11 delivery agents and 4 advisory experts
- Continuous flow over fixed sprints as the delivery model (ADR-010)
- `AGENTS.md` plus `.opencode/rules/` covering Clean Architecture, Security by Design and project conventions
- Product requirements: `docs/requirements/PRD-eop-card-game.md` defines the game, its scope, its trick-taking domain model and its open risks, and is backed by epic EOP-5. The rules are sourced from Microsoft's shipped instructions, score sheet and the author's whitepaper, all committed under `docs/EoP_Microsoft_Docs/` with their CC-BY-3.0 US licence recorded
- Commit convention enforcement: `.githooks/commit-msg` requires `[EOP-NNN] <type>: <summary>`, activated per clone via `core.hooksPath`
- Tool governance: explicit deny-then-allow-lists for `github_*` and `graphify_*` in `.opencode/opencode.json`

### Fixed

- **Liquibase had never run.** `liquibase-core` was on the classpath and `spring.liquibase.*` was configured, but Spring Boot 4 moved `LiquibaseAutoConfiguration` into a separate `spring-boot-liquibase` module that was never added. Every migration property was inert. Nothing failed, because an unread changelog and an empty one are indistinguishable until the first migration exists — which is exactly when this surfaced
- **The master changelog matched no files.** `includeAll` used an absolute `classpath:` path that silently resolved to zero changesets; it now resolves relative to the changelog file and is filtered to `.xml`, so the `.gitkeep` placeholder that previously sat in `changes/` cannot crash startup once the directory is genuinely read

### Decided, not yet implemented

Recorded here so that a decision is never mistaken for working code. Each of these
has an accepted ADR or rule file but no implementation in `src/` or `pom.xml`.

- Configuration management via `@ConfigurationProperties` + `@Validated` — rule only, no such class exists
- Resilience patterns: Resilience4j retry, circuit-breaker and time-limiter — `.opencode/rules/resilience.md` records the intent, but the dependency is absent from `pom.xml`
- Front-end stack: React + TypeScript + Vite + GOV.UK Design System CSS (ADR-009) — no `ui/` directory exists
- Automated deployment: the pipeline publishes a deployable image but does not deploy it. Rolling the new tag onto the instance is a manual pull-and-restart over SSH (ADR-012 records why CI-driven SSH deployment is deliberately avoided)

[1.0.0-SNAPSHOT]: https://github.com/maglez/eop-threat-medeling/releases/tag/v1.0.0-SNAPSHOT
