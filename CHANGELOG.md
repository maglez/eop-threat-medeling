# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-SNAPSHOT] — Unreleased

### Added

- Walking Skeleton: Spring Boot 3.4.4 + Java 21 + `GET /health` (ADR-002)
- CI/CD pipeline: GitHub Actions with `mvn verify` on push/PR
- GitHub MCP integration for agent-based PR/repo management (ADR-003)
- API contract-first with OpenAPI 3.1 + springdoc (ADR-004)
- Error handling with RFC 9457 Problem Details (ADR-005)
- Build quality gates: Checkstyle, SpotBugs, JaCoCo 80/70, Enforcer (ADR-006)
- Configuration management rules (`@ConfigurationProperties` + `@Validated`)
- Resilience patterns: Resilience4j (retry, circuit-breaker, time-limiter)
- Multi-agent system: 17 agents defined in `.opencode/agents/`
- Graphify knowledge graph for context optimisation
- AGENTS.md with Clean Architecture, Security by Design, project conventions
- Database migration strategy: Liquibase with XML changelogs, H2 in dev, PostgreSQL in prod (ADR-008)
- Load testing: k6 with InfluxDB + Grafana monitoring stack (Docker Compose), auto-provisioned dashboard, SLO thresholds (p95 < 200ms)

[1.0.0-SNAPSHOT]: https://github.com/maglez/eop-threat-medeling/releases/tag/v1.0.0-SNAPSHOT
