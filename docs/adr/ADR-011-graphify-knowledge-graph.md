# ADR-011: Graphify Knowledge Graph via Repo-Local MCP Server

**Status:** Accepted  
**Date:** 2026-08-03  
**Deciders:** @team-member-tech-lead

## Context
Three agents — `@team-member-tech-lead`, `@team-member-code-reviewer`, `@team-member-architecture-guardian` — are instructed to reason about blast radius, coupling, and impacted components before acting. Doing that from `grep` alone is unreliable once a codebase outgrows a single context window.

[Graphify](https://www.npmjs.com/package/@sentropic/graphify) builds a knowledge graph from a repository (code symbols plus git history) and can expose it over MCP. It was wired up incrementally across several changes; this ADR records the decisions retroactively, because the reasoning lived only in the Blueprint, which is documentation rather than a decision record.

The honest starting condition matters: this repository currently holds **88 lines of Java across 4 files**. The graph is being installed ahead of the domain code it is meant to describe.

## Decision

### Installation and process model
- **Pin the CLI repo-locally** in `tools/graphify/package.json` at exactly `0.17.1`, bootstrapped with `cd tools/graphify && npm install --ignore-scripts`. A global install would drift per machine and per developer; an exact pin makes the graph format reproducible. `node_modules/` is gitignored.
- **Use `graphify serve` as an MCP server**, registered in `.opencode/opencode.json` as `mcp.graphify`, rather than hand-rolling a plugin that shells out to the CLI and parses stdout. The upstream tool contract is maintained; a bespoke parser would not be.
- **Reject `graphify opencode install`.** That command writes into OpenCode's own configuration on our behalf. Our config is reviewed and committed, so a tool mutating it out of band is the wrong direction of control.
- **Use a repo-relative command path** — `tools/graphify/node_modules/.bin/graphify` — so MCP startup does not depend on direnv having exported `PATH`. `.envrc` still adds the same directory to `PATH` for interactive shell use, but the two paths are independent on purpose.
- **Keep the local `.opencode/plugins/graphify.js` reminder plugin.** It prepends a one-off `echo` to the first `bash` command of a session when a graph exists. It predates the MCP server and is now partly redundant, but removing it would also remove the only `tool.execute.before` surface we have, and its cost is one `echo`.

### Enrichment
- **Assistant mode for descriptions and community labels**, not `--description-mode direct` / `--label-mode direct`. Direct mode calls a model per node; assistant mode writes prompt batches to `.graphify/description-instructions/` and `.graphify/label-instructions/` for the agent already in session to answer. Cost is zero extra API calls.
- **Pin English explicitly:** `graphify update . --description-lang en --label-lang en`. The default `auto` means "detect per source", and detection mislabelled English Java and k6 files as Portuguese, emitting `lang=pt` markers into the batch prompts. Pinning removes the markers at source rather than correcting them by hand afterwards.
- **Enrich only grounded code symbols.** Of the four description batches generated, three consisted almost entirely of commit and branch entries whose descriptions could only restate their own titles. Answering batch-003 plus the two code symbols in batch-000 produced **26/26 describable nodes described, skipped 0**. Graphify's own anti-hallucination policy excludes the remaining 125 entity nodes because they carry no citations or evidence — so the describable set and the set worth describing turned out to be identical.
- **Use `--fill-missing` for future domain code.** It describes only nodes whose description is empty and is idempotent, so enrichment does not have to be redone wholesale as the domain grows.

### Governance
- **Govern `graphify_*` with a deny-then-allow-list** in `.opencode/opencode.json`, mirroring the shape already used for `github_*`: deny the wildcard, then allow each of the 11 read tools by name. All 11 tools present in `0.17.1` are read-only, so this changes nothing today; it means a mutating tool added in a future version is denied until reviewed, instead of inheriting the default allow.
- **The four advisory experts keep graph access while remaining denied `github_*`.** This asymmetry is deliberate, not an oversight: the graph is derived from the repository the experts are already reading, whereas GitHub is a live external system with side effects and rate limits. Their value is opinion on code they can see.
- **Install a `commit-msg` hook, but not Graphify's `post-commit` hook.** `.githooks/commit-msg` enforces the `[EOP-NNN]` prefix required by Blueprint §7.5. Graphify's `post-commit` rebuild is deliberately *not* installed: its fast path rebuilds topology without descriptions or labels, which would silently discard the enrichment above on every commit. Graph refresh stays an explicit `graphify update . --description-lang en --label-lang en`.

## Consequences

- **Positive:** All 11 tools are confirmed live by direct invocation, not inferred from documentation: `graphify_first_hop_summary`, `graph_stats`, `query_graph`, `get_node`, `get_neighbors`, `get_community`, `god_nodes`, `shortest_path`, `review_delta`, `review_analysis`, `recommend_commits`.
- **Positive:** Enrichment is complete and verified — `graphify check-update` reports "Graph state looks current", and `.graphify/.graphify_describe_pending` is gone. Assistant mode reaching full coverage means the zero-cost path was not a compromise on completeness.
- **Positive:** The nine communities carry meaningful names (for example "Spring Boot Walking Skeleton", "k6 Load Test Configuration") and those names *do* reach agents through `get_node` and `get_community` output.
- **Negative — the graph is mostly git metadata.** Of 534 edges, 513 (96%) are `ON_BRANCH` (360), `PARENT_OF` (140) and `MODIFIES` (13). Only 21 edges describe code structure: `method` (10), `contains` (9), `imports` (2). Six of the eight highest-degree nodes are test classes or k6 constants.
- **Negative — retrieval is currently noise-dominated.** `query_graph("health endpoint", depth=2)` returns **138 of 151 nodes**, with the relevant `.health()`, `Main` and `health-check.js` nodes buried under ~20 merge-commit nodes. Until the domain code grows, `grep` and direct file reads are the better tool, and `.graphify/GRAPH_REPORT.md` says so on line 4: "Corpus is ~40,457 words - fits in a single context window. **You may not need a graph.**"
- **Negative — descriptions are stored but not surfaced over MCP.** `graph.json` holds a `description` on all 26 enriched nodes, but neither `get_node` nor `query_graph` renders it in `0.17.1`; they emit ID, source, type, community and degree only. The descriptions therefore reach humans reading `GRAPH_REPORT.md` and not the agents they were written for. Re-evaluate on the next Graphify upgrade.
- **Neutral:** Two setup steps are per-clone and cannot be committed: the `tools/graphify` bootstrap and `git config core.hooksPath .githooks`. Both are documented in `SETUP.md` and `docs/devops/local-development.md`; a developer who skips them gets no `graphify_*` tools and an inert hook, in both cases silently.
- **Neutral:** `.graphify/` is gitignored, so the graph is a local artifact rebuilt per clone rather than shared state to keep in sync.
- **Known upstream wording defects, harmless but misleading:** `graphify check-update` attributes a label-less rebuild to "the fast git hook" even though no hooks are installed here, and advises "Run the graphify skill with --update" although no such skill exists in `.opencode/`. The correct action is always `graphify update .` with the language flags.

## Related
- [opencode.json](../../.opencode/opencode.json)
- [Blueprint §5 Graphify](../../.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md)
- [ADR-003: GitHub MCP Integration](ADR-003-github-mcp-integration.md) — the `github_*` allow-list this ADR's permission block mirrors
- [Local Development Guide](../devops/local-development.md)
- [SETUP.md](../../SETUP.md)
