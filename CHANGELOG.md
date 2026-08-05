# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-SNAPSHOT] — Unreleased

### Added

Working code and tooling that exists in the repository today.

- Walking Skeleton: Spring Boot 4.1.0 + Java 21 + `GET /health` (ADR-002)
- Continuous integration: GitHub Actions running `./mvnw verify` on push and PR, uploading the built artifact, then building the container image, smoke testing it against a real PostgreSQL and publishing it to GHCR on `main`
- Front end: React + TypeScript + Vite under `ui/` with GOV.UK Design System CSS (ADR-009). One page — the application shell plus the card catalogue fetched live from the API. Both calls to action are visibly disabled, because a button that looks live and does nothing is worse than one that admits it
- Single-origin serving: Caddy serves the built front end and reverse-proxies `/api/*` and `/health` to the application on the same origin (ADR-017). The application container publishes no host port at all, so there is no cross-origin request anywhere and therefore no CORS configuration to maintain
- Front-end quality gates: a dedicated `ui` CI job runs type checking, linting, unit tests and the production build as four separate steps. It is deliberately separate from the Java `build` job — the single required status check protecting `main` stays Java-only and fast, and a front-end failure reports as a front-end failure
- Container images: multi-stage `Dockerfile` producing a non-root JRE image and `ui/Dockerfile` producing a Caddy image carrying the built front end, `compose.app.yml` running both alongside containerised PostgreSQL, published to `ghcr.io` by CI with no repository secrets (ADR-012, ADR-017)
- Deployment infrastructure: Terraform under `infra/` describing a single `t3.small` EC2 instance in a dedicated VPC with an Elastic IP and a separate encrypted EBS data volume (ADR-012). Validated with `terraform validate`; no `apply` has run yet because no AWS account has been provisioned, and cloud deployment is deferred in favour of running locally (see the ADR-012 amendment)
- Local container runtime: Colima provides a Docker-compatible daemon from Homebrew formulae with no administrator rights (ADR-016). The whole stack — application and PostgreSQL — now runs on a developer machine with the same `docker compose -f compose.app.yml up -d` that CI and the EC2 bootstrap script use
- Build quality gates: Checkstyle (`checkstyle.xml`), SpotBugs, JaCoCo 80% instruction coverage, Enforcer (ADR-006)
- Database migrations: Liquibase with a master XML changelog, H2 in dev, PostgreSQL in prod (ADR-008). The first migration creates the `card` table; the second replaces the placeholder deck with the real one
- Card catalogue: `GET /api/v1/cards` and `GET /api/v1/cards/{cardId}` serve the deck as read-only reference data (EOP-6)
- The real Elevation of Privilege deck: all 78 cards, thirteen ranks in each of the six STRIDE suits, transcribed from Microsoft's published deck and seeded by an additive data-only migration (EOP-13). © 2009 Microsoft Corporation, licensed CC-BY-3.0 US — attribution is the only obligation the licence imposes and it is discharged in the running application, not merely in the repository. The printed deck held 74 because two suits started above the two; the published machine-readable file completes them to thirteen apiece, which also removes two suit-specific special cases from the trick rules still to be written
- API contract: the hand-authored OpenAPI 3.1 specification at `docs/api/openapi.yml` (ADR-004), written before the controllers that implement it
- Error handling: a single `@RestControllerAdvice` rendering RFC 9457 problem details, with a unit test for every mapped exception (ADR-005)
- Feature flags: decided as Spring configuration properties under `eop.features.*` (ADR-013). No flag exists yet — the first arrives with the first live deployment
- Real-time transport and player identity: decided from a time-boxed spike that ran a real server-sent-events endpoint against the application (EOP-8). Server-sent events carry state to every connected player (ADR-014); a server-issued opaque token in per-tab session storage identifies a player (ADR-015). Both are decisions only — the spike code was deleted and no production code graduated from it
- Load testing: k6 with SLO thresholds (p95 < 200ms), run against the container through the reverse proxy — the path a real user takes. The health endpoint measures p95 9.85ms against the 200ms threshold with 663 of 663 checks passing. The earlier 5.77ms figure measured the application's own port, which no longer exists, so `docs/performance/TRENDS.md` records a deliberate baseline reset rather than a regression. The InfluxDB + Grafana stack starts and is provisioned, but k6 results never reach it — see Known issues
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
- **Documentation named an uninstallable prerequisite.** `SETUP.md`, `docs/devops/local-development.md` and `.opencode/rules/performance-testing.md` all instructed the reader to install or launch Docker Desktop, which needs administrator rights this project does not have and a paid licence above an organisation-size threshold. All three now describe Colima (ADR-016)
- **The two Compose files shared one project namespace.** Both derived their project name from the directory, so each stack reported the other's containers as orphans and `docker compose down --remove-orphans` on either would have destroyed the other. They are now named `eop-app` and `eop-monitoring` explicitly
- **`.env.example` pointed k6 at a container hostname.** `INFLUXDB_URL` was `http://influxdb:8086`, which cannot resolve from the host where k6 runs, and omitted the `/k6` database path
- **The ADR index claimed four delivered decisions were unimplemented.** The OpenAPI contract, the RFC 9457 handler and the first Liquibase changeset all shipped with the card catalogue, and the index still read "No" for each; the front-end row read "No" since July. `docs/adr/README.md` now matches what is on disk
- **The attribution footer disclaimed the content it now has to attribute.** It read "The cards shown above are placeholders written for this project, not Microsoft's" — true for the six invented prompts, and false the moment the real deck was seeded. An attribution notice that denies authorship of the work it credits is worse than none
- **`docs/performance/TRENDS.md` held placeholder rows.** Every cell had been an em dash since the file was created, because the load test had never run against a container. It now carries measured figures and records the baseline reset

### Known issues

- **k6 results never reach InfluxDB, so the Grafana dashboard is empty.** Three independent causes: the sample environment pointed at a container hostname (fixed here); direnv exports `.env` at directory entry so an already-running shell keeps the stale value; and InfluxDB has HTTP authentication enabled while the k6 output URL carries no credentials — a direct write with the configured admin credentials returns `401`. k6 logs one write error per flush interval but **exits 0**, so thresholds still gate correctly and nothing checking the exit code ever noticed. The JSON and summary files written to `docs/performance/history/` are the real evidence. Repair needs its own story

### Decided, not yet implemented

Recorded here so that a decision is never mistaken for working code. Each of these
has an accepted ADR or rule file but no implementation in `src/` or `pom.xml`.

- Configuration management via `@ConfigurationProperties` + `@Validated` — rule only, no such class exists
- Resilience patterns: Resilience4j retry, circuit-breaker and time-limiter — `.opencode/rules/resilience.md` records the intent, but the dependency is absent from `pom.xml`
- Automated deployment: the pipeline publishes a deployable image but does not deploy it. Rolling the new tag onto the instance is a manual pull-and-restart over SSH (ADR-012 records why CI-driven SSH deployment is deliberately avoided)

[1.0.0-SNAPSHOT]: https://github.com/maglez/eop-threat-medeling/releases/tag/v1.0.0-SNAPSHOT
