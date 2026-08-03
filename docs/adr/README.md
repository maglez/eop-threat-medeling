# Architecture Decision Records

Each ADR captures one decision, the context that forced it, and the consequences
accepted alongside it. ADRs are immutable references: they are amended in place with
a dated note rather than rewritten, and they are **never renumbered**, because their
numbers are cited from the Blueprint, the CHANGELOG and commit messages.

## Index

| ADR | Decision | Status | Implemented? |
|---|---|---|---|
| [002](ADR-002-spring-boot-bootstrap.md) | Spring Boot Walking Skeleton | Accepted | Yes |
| [003](ADR-003-github-mcp-integration.md) | GitHub MCP integration | Accepted (amended 2026-07-28) | Yes |
| [004](ADR-004-api-contract-first.md) | API contract-first with OpenAPI 3.1 | Accepted | No — `docs/api/openapi.yml` not yet written |
| [005](ADR-005-error-handling-strategy.md) | Error handling via RFC 9457 Problem Details | Accepted | No — no handler exists |
| [006](ADR-006-build-quality-gates.md) | Build quality gates | Accepted | Yes |
| [007](ADR-007-versioning-strategy.md) | Semantic Versioning | Accepted | Yes |
| [008](ADR-008-database-migration-liquibase.md) | Database migrations with Liquibase | Accepted | Partly — wiring exists, no migrations written |
| [009](ADR-009-frontend-react-typescript.md) | React + TypeScript + Vite + GOV.UK Frontend | Accepted | No — no `ui/` directory |
| [010](ADR-010-continuous-flow-over-sprints.md) | Continuous flow over sprint timeboxes | Accepted | Yes |
| [011](ADR-011-graphify-knowledge-graph.md) | Graphify knowledge graph via repo-local MCP server | Accepted | Yes |
| [012](ADR-012-deployment-target.md) | Deployment to a single EC2 instance with Terraform | Accepted | Partly — image, Compose and Terraform exist; no `apply` has run |

The "Implemented?" column exists because an accepted ADR is a decision, not a
delivery. `CHANGELOG.md` separates the same two things for the same reason.

## Why numbering starts at 002

There is no ADR-001. The first architectural decision — adopting Spring Boot as the
application framework — was recorded as ADR-002 and 001 was never written. Rather
than renumber a set of documents that other files reference by number, the gap is
recorded here and left alone.

## Adding an ADR

1. Take the next free number. Do not reuse a number, even for a superseded ADR.
2. Name the file `ADR-NNN-kebab-case-title.md`.
3. Follow the house structure: `# ADR-NNN: Title`, then `**Status:**`, `**Date:**`
   and `**Deciders:**`, then `## Context`, `## Decision`, `## Consequences`,
   `## Related`.
4. State consequences honestly, including the negative ones. An ADR that lists only
   benefits has not recorded a decision, only an advertisement.
5. Add a row to the index above.
6. To reverse a decision, write a new ADR that supersedes the old one and mark the
   old one `Superseded by ADR-NNN`. Do not edit the original's decision.
