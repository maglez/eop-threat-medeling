# Architecture Decision Records

Each ADR captures one decision, the context that forced it, and the consequences
accepted alongside it. ADRs are immutable references: they are amended in place with
a dated note rather than rewritten, and they are **never renumbered**, because their
numbers are cited from the Blueprint, the CHANGELOG and commit messages.

## Index

| ADR | Decision | Status | Implemented? |
|---|---|---|---|
| [002](ADR-002-spring-boot-bootstrap.md) | Spring Boot Walking Skeleton | Accepted (framework version superseded 2026-07-27) | Yes |
| [003](ADR-003-github-mcp-integration.md) | GitHub MCP integration | Accepted (amended 2026-07-28) | Yes |
| [004](ADR-004-api-contract-first.md) | API contract-first with OpenAPI 3.1 | Accepted | Yes — `docs/api/openapi.yml` hand-authored before the first controller |
| [005](ADR-005-error-handling-strategy.md) | Error handling via RFC 9457 Problem Details | Accepted | Yes — `GlobalExceptionHandler` maps every 4xx and 5xx |
| [006](ADR-006-build-quality-gates.md) | Build quality gates | Accepted | Yes |
| [007](ADR-007-versioning-strategy.md) | Semantic Versioning | Accepted | Yes |
| [008](ADR-008-database-migration-liquibase.md) | Database migrations with Liquibase | Accepted (amended 2026-08-10) | Yes — autoconfiguration fixed and the first changeset applies; the H2 console consequence is withdrawn |
| [009](ADR-009-frontend-react-typescript.md) | React + TypeScript + Vite + GOV.UK Frontend | Accepted | Yes — `ui/` scaffolded, built and served |
| [010](ADR-010-continuous-flow-over-sprints.md) | Continuous flow over sprint timeboxes | Accepted | Yes |
| [011](ADR-011-graphify-knowledge-graph.md) | Graphify knowledge graph via repo-local MCP server | Accepted | Yes |
| [012](ADR-012-deployment-target.md) | Deployment to a single EC2 instance with Terraform | Accepted (deployment target withdrawn 2026-08-10) | Partly — image and Compose run locally; Terraform validates but no `apply` has run |
| [013](ADR-013-feature-flags.md) | Feature flags via Spring configuration properties | Accepted | Yes — `eop.features.session-lifecycle` withholds `SessionController` via `@ConditionalOnProperty`; `SessionControllerDisabledIntegrationTest` asserts the bean is absent and all five routes 404 |
| [014](ADR-014-realtime-transport.md) | Real-time transport via server-sent events | Accepted | Yes — `SseSessionEventPublisher` emits `player-joined` and `game-started`; verified on a real socket through Caddy, `retry:3000` and `:heartbeat` frames included, events carrying no state |
| [015](ADR-015-player-identity.md) | Player identity via a server-issued opaque token in session storage | Accepted | Partly — the server half is done: 43-character token issued on admission, only its SHA-256 digest stored, no response or event carries the digest, and a missing credential is refused identically to a wrong one. The `sessionStorage` per-tab custody half is not built — `ui/` holds only a health-check shell and the string `sessionStorage` appears nowhere in it, so nothing yet keeps a token across a refresh. EOP-11 delivers it |
| [016](ADR-016-local-container-runtime.md) | Colima as the local container runtime | Accepted | Yes — installed, stack runs locally, all verification gates executed |
| [017](ADR-017-frontend-delivery-topology.md) | Front-end delivery via Caddy on a single origin | Accepted | Yes — proxy serves the site and forwards the API |
| [018](ADR-018-uuid-v7-identifiers.md) | UUID v7 primary keys generated through an application port | Accepted | Yes — `IdentifierGenerator` mints identifiers in the use case, not at flush; `UuidV7IdentifierGeneratorTest` pins the version nibble, the variant and time ordering across 2,000 draws |
| [019](ADR-019-session-lifecycle-and-join-codes.md) | Session lifecycle, join codes, and header-only authentication on the event stream | Accepted | Yes — implemented across EOP-10: Crockford base32 codes, seat contention settled by `uq_player_session_seat`, throttled guessing, and one indistinguishable refusal for every unusable code |
| [020](ADR-020-session-concurrency-control.md) | Concurrency control by compare-and-set on `status`, not by optimistic locking | Accepted | Yes — `touchWhileInStatus` and `advanceStatus` are status-guarded conditional updates whose rows-affected count is the protocol; `@Version` is mapped but deliberately unenforced, and no `OptimisticLockingFailureException` handler exists anywhere |
| [021](ADR-021-trusted-proxy-forwarded-for.md) | `X-Forwarded-For` trusted only from an explicit, default-denied allow-list of proxies | Accepted | Yes — `eop.web.trusted-proxies` defaults to the empty list, `ClientAddressResolver` ignores the header unless the peer is allow-listed, a malformed or non-canonical entry fails startup, and `ForwardedForThrottleBypassIntegrationTest` asserts both halves: the rotation attack is throttled, and the header is honoured when the peer is configured |
| [022](ADR-022-agent-model-tier-governance.md) | Model tiers allocated by Definition-of-Done role, not by artefact type | Accepted | Yes — `@tester-api` pinned to `{env:MODEL_B}`, all five DoD gates on `MODEL_A`/`MODEL_B`, and each carries a Sign-off Contract in its own definition. The narrowing of the Separation Invariant to exclude test code is recorded rather than denied, and `AUDITOR_AGENTS`/`READ_ONLY_AGENTS` in `tools/agent-trace.py` are split so the write check no longer false-alarms on a tester writing a test |

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
5. Add a row to the index above, and keep its Status cell in step with the ADR's own
   `**Status:**` line — including the date whenever an amendment changes the status.
   An amendment that leaves the status word alone, such as withdrawing a consequence
   that was never true, needs no date in either place; that is why several ADRs here
   record a dated amendment — some under a `## Amendments` heading, one as an inline
   `**Amendment, YYYY-MM-DD …**` paragraph — above a bare `Accepted`.
   `AdrIndexConsistencyTest` fails the build if a file has no row, a row points at no
   file, a file carries anything other than exactly one `**Status:**` line, the leading
   status word disagrees, or a dated status line reaches the index without its date.
   It checks that last rule in one direction only — an undated status line is always
   accepted. That test exists because this step was drifted past twice.
6. To reverse a decision, write a new ADR that supersedes the old one and mark the
   old one `Superseded by ADR-NNN`. Do not edit the original's decision.
