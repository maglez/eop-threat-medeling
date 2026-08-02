# AGENTS.md

## Default Architecture: Clean Architecture

All code must follow Clean Architecture — dependencies point inward. Cross-boundary data uses DTOs, not entities. See `.opencode/rules/clean-architecture.md` for the full rule set.

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

- **Java 21 LTS** / **Spring Boot 4.1.0** — single module, groupId `org.maglez`
- **Build:** `./mvnw` (Maven Wrapper — no global Maven install required)
- **Entrypoint:** `src/main/java/org/maglez/Main.java` — `@SpringBootApplication` with `GET /health`
- **Test framework:** JUnit 5 + Spring Boot Test — `src/test/resources/application.properties` overrides the datasource so tests run hermetically (no `DB_*` env vars needed)
- **Commands:**
  - `./mvnw test` — run all tests
  - `./mvnw compile` — fast compile check
  - `./mvnw spring-boot:run` — start application on port 8080
- Domain lives under `org.maglez.eop.*` — STRIDE categories, threat cards, privilege escalation rules
- Tests mirror source at `src/test/java/org/maglez/eop/`
- **Commits:** every message MUST start with the uppercase Jira key `[EOP-NNN]`, then `<type>: <summary>` — see `.opencode/rules/git-commits.md`
- **Front-end:** *not scaffolded yet.* ADR-009 selects React + TypeScript + Vite under `ui/` with GOV.UK Design System CSS, but no `ui/` directory exists
- **OpenCode plugins** (pinned in `.opencode/opencode.json`): VibeGuard (secret redaction), DCP (context pruning), Supermemory (cross-session memory), type-inject (TypeScript types), scheduler (cron jobs), goal-plugin (`/goal` auto-continue) — see Blueprint §12. Graphify (knowledge graph) is a local plugin in `.opencode/plugins/`, not an npm entry.
- **MCP prerequisite:** `uv` must be installed (`brew install uv`). The Atlassian MCP server is launched as `uvx mcp-atlassian`; without `uvx` on `PATH` it silently fails and no `atlassian_jira_*` tools are available. MCP servers are registered only at session start, so restart OpenCode after installing.

## OpenCode Agent System

This project uses a multi-agent team defined in `.opencode/agents/`. Eleven delivery agents:
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

Plus four advisory experts, with no Jira or GitHub access — invoke them by name for a second opinion:
- `@expert-uncle-bod` — software craftsmanship, SOLID, Clean Architecture
- `@expert-dave-farley` — continuous delivery, TDD, fast feedback
- `@expert-kent-beck` — TDD, XP, incremental refactoring
- `@expert-alex-xu` — ultra-high-scale distributed systems and storage

All agents are `mode: subagent` except `team-member-tech-lead`, which is `mode: all` — it is
selectable in the **Tab** primary-agent cycle *and* still dispatchable via `@` / the Task tool.

**Always launch `opencode` from the repository root.** OpenCode scans `.opencode/agents/`
recursively for `*.md`, but bootstraps its plugins and goal state into `$PWD/.opencode/`. Starting
OpenCode from inside `.opencode/` makes those two paths collide, so ~30 dependency
`README.md`/`CHANGELOG.md` files get registered as phantom agents and pollute the Tab cycle.

Two guards enforce this, because documentation alone did not prevent a recurrence:

1. **`opencode()` shell wrapper** (in `~/.zshrc`, per-developer, not in the repo) resolves
   `git rev-parse --show-toplevel` and launches OpenCode from there via a subshell `cd`, so the
   calling shell's working directory is unchanged. Outside a git repo it passes through untouched.
   Note the `opencode [project]` positional argument only works for the default TUI command, not
   for subcommands like `run`, which is why the wrapper uses `cd`.
2. **`.opencode/agents/.opencode` sentinel** — a committed *regular file*, not a directory. It
   makes the nested tree physically impossible to create: launching from the wrong directory now
   fails fast with `BadResource: FileSystem.readFile (.../.opencode/agents/.opencode/opencode.json)`
   instead of silently registering phantoms. Do not delete it and do not turn it into a directory.

The `.opencode/**/.opencode/` rule in `.gitignore` only keeps `git status` clean — it does not stop
phantom registration, and its trailing slash means it does not match the sentinel file.
