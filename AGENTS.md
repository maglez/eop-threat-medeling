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
- **Front-end: shipped, not pending.** `ui/` is a tracked React + TypeScript + Vite front end with GOV.UK Design System CSS (ADR-009), and it is built, tested, containerised and served. It is the *only* front end — put UI work inside it rather than scaffolding a second one or picking a different toolchain. Every claim here is checkable against a tracked file. **Stack —** `ui/package.json`: `react ^18.3.1` (**18, not the 19 ADR-009's Stack components section claims**), `typescript ^5.6.3`, `vite ^7.3.6`, `govuk-frontend ^5.7.1`, `engines.node >= 22.12`; the GOV.UK assets are copied in by `ui/scripts/copy-govuk-assets.mjs`, wired as the `prebuild`/`predev` scripts. **Location —** components in `ui/src/components/`, entry `ui/index.html` → `ui/src/main.tsx`, strict-mode TypeScript (`ui/tsconfig.json`). The typed DTO layer and every `fetch` wrapper live in the single module `ui/src/api.ts`: there is **no** `ui/src/types/`, `pages/`, `hooks/` or `services/` directory, so do not go looking for the layout ADR-009 sketches (corrected in its 2026-08-19 amendment). **Build —** `ui/vite.config.ts` emits to `ui/dist/` and runs a dev server on **`:5173`** (Vite's default) proxying `/api` and `/health` to Spring Boot on `:8080`; `npm run verify` in `ui/` chains typecheck, ESLint, Vitest and a production build, and `.github/workflows/ci.yml` runs those as a dedicated `ui` job separate from the Java `build` job. **Serving —** `ui/Dockerfile` builds the bundle with Node and serves it with Caddy, `ui/Caddyfile` serves the built assets at `/` and reverse-proxies `/api/*` and `/health` to the API so both sit on one origin and no CORS configuration exists anywhere in the project (ADR-017), and `compose.app.yml` composes that image alongside the API
- **All fifteen `.opencode/rules/*.md` files are already in your context — every agent, every subagent dispatch.** `.opencode/opencode.json` loads the directory with one glob, so do not spend a `read` call re-reading a rule, and never treat a rule as inapplicable on the grounds that you have not opened it. Two consequences: a new file in `.opencode/rules/` is binding the moment it is committed, with no per-agent list to keep in sync; and the directory must contain rules only, because the glob takes everything in it. This replaced a split where four rules were global and eleven were named in per-agent `## Required Reading` sections — a trigger no agent could evaluate without first reading the file, which left nine of fifteen unread and cost two review rounds on EOP-14. Rationale and the superseded design are in Blueprint §7.6.
- **OpenCode plugins** (pinned in `.opencode/opencode.json`): VibeGuard (secret redaction), DCP (context pruning), Supermemory (cross-session memory), type-inject (TypeScript types), scheduler (cron jobs), goal-plugin (`/goal` auto-continue) — see Blueprint §12. Graphify (knowledge graph) is not an npm plugin entry — the CLI is pinned repo-locally in `tools/graphify/` and placed on `PATH` by `.envrc`, and `.opencode/plugins/graphify.js` is a directory-loaded local plugin. Graphify's own minimum is Node ≥ 20, but do not read that as the version to install: the repository floor is **22.12**, set by the front end (`ui/package.json` `engines.node >= 22.12`, itself set by Vite 7's `^20.19.0 || >=22.12.0`) and built on 22 in CI (`.github/workflows/ci.yml`, where `node-version: 22` resolves to the latest 22.x and so clears the patch floor). The patch level is load-bearing rather than pedantic — Node 22.0–22.11 satisfies a bare ">= 22" while violating Vite's own range. Do not expect `npm install` to stop you, though: `engine-strict` is unset and there is no `.npmrc` at the repository root or in `ui/`, so npm prints an `EBADENGINE` **warning** and installs anyway, leaving the mismatch to surface later as a confusing Vite failure instead of at install time. Node 20 reached end-of-life on 2026-04-30. See Blueprint §5.
- **Autonomous completion is gated, not self-declared.** Under `/goal` the Tech Lead runs autonomously but cannot declare a story done on its own: it must collect explicit approvals from all five of @tester-unit-and-quality, @tester-api, @security-auditor, @code-reviewer and @architecture-guardian, and `./mvnw verify` counts as one piece of evidence rather than the gate. The goal-plugin's `completionAudit` then spawns an independent read-only auditor that must approve the evidence, and rejection pauses the goal instead of archiving it. Two known limitations: the auditor inherits `MODEL_A` — the *same model ID* as `tech-lead`, not merely the same family, so by Blueprint §3.1's definitions that audit is neither family- nor model-independent — and cannot be repinned, and it has no `bash`/`task` access so it inspects evidence rather than re-running checks. **Never create `.opencode/agents/goal-verify.md`** — it makes the plugin throw at startup. Full rationale in Blueprint §12.8.
- **Model tiers are `MODEL_A`–`MODEL_E`, and there are only four distinct models.** `.opencode/opencode.json` pins every agent through one of five environment variables (all five are asserted by `.envrc`), but **`MODEL_E` is deliberately the same model ID as `MODEL_C`** — `ui-builder` shares the coder tier rather than having one of its own. The separate variable exists so the front-end can be repointed later without disturbing the back-end agents; do not assume five distinct tiers exist, and do not "fix" the duplication. The `agent` block is grouped by tier, so move an agent between groups rather than editing its value in place.
- **A review gate must not share weights with an agent that writes production code — a rule with two documented exceptions, not an absolute.** It binds *delegated* authoring, and `MODEL_A` writes Java, so the exceptions are real: production code the primary agent or `@tech-lead` authors itself shares `MODEL_A` with two of the five gates, and test code shares `MODEL_B` with `@code-reviewer`. Both are recorded in [ADR-022](docs/adr/ADR-022-agent-model-tier-governance.md), which also defines the two degrees of independence — *family*-independent versus the weaker *model*-independent — that any claim about review separation must be stated in. Cite this rule with its exceptions and use `/trace` to find which case a story is in. Both `tester-unit-and-quality` and `tester-api` were moved from `MODEL_C` to `MODEL_B` for this reason: `MODEL_A` writes Java, `MODEL_C` writes migrations and CI/IaC, `MODEL_E` writes the front-end, and `MODEL_B` authors no production code at all. On `MODEL_C` `tester-unit-and-quality` needed three dispatches to return a verdict and once recommended merging a red build; `tester-api` then failed the same way on the same tier under EOP-26 — four dispatches, `VERDICT: APPROVE` every time but with none of the contracted evidence, once substituting headings of its own for the brief's required outputs and once claiming its evidence had been "compiled in a markdown document" it was never permitted to write. **All five DoD gate agents therefore belong on `MODEL_A` or `MODEL_B`; none may sit on `MODEL_C`/`MODEL_E`.** The other half of that fix is in the agent definitions themselves: all five gate agents now carry a **Sign-off Contract** (a mandatory final `VERDICT: APPROVE`/`VERDICT: REJECT` line, severity-tagged findings with `file:line`, actual command output rather than intent, answer the brief's enumerated outputs rather than a structure of your own, the reply is the only deliverable so never claim a file was written, never end with a question, never approve a red build) and a **read-only-while-reviewing** rule. Both were previously enforced only by whatever the dispatching prompt happened to say, which is why they were skipped. `performance-engineer` remains on `MODEL_C` and carries no Sign-off Contract — it is not one of the five gates, and dispatching it as one is unsupported.
- **MCP prerequisite:** `uv` must be installed (`brew install uv`). The Atlassian MCP server is launched as `uvx mcp-atlassian`; without `uvx` on `PATH` it silently fails and no `atlassian_jira_*` tools are available. MCP servers are registered only at session start, so restart OpenCode after installing.

## OpenCode Agent System

This project uses a multi-agent team defined in `.opencode/agents/`. Eleven delivery agents:
- `@product-owner` — requirements discovery and backlog management
- `@tech-lead` — engineering orchestration and enforcement
- `@architecture-guardian` — C4 models and ADRs
- `@devops-engineer` — infrastructure and CI/CD
- `@db-designer` — database schemas and migrations
- `@ui-builder` — accessible front-end components
- `@tester-unit-and-quality` — unit test automation
- `@tester-api` — API integration tests
- `@security-auditor` — security and vulnerability audits
- `@code-reviewer` — static code review and SOLID compliance
- `@performance-engineer` — benchmarks and load testing

Plus four advisory experts, with no Jira or GitHub access — invoke them by name for a second opinion:
- `@expert-uncle-bod` — software craftsmanship, SOLID, Clean Architecture
- `@expert-dave-farley` — continuous delivery, TDD, fast feedback
- `@expert-kent-beck` — TDD, XP, incremental refactoring
- `@expert-alex-xu` — ultra-high-scale distributed systems and storage

All agents are `mode: subagent` except `tech-lead` and `product-owner`,
which are `mode: all` — they are selectable in the **Tab** primary-agent cycle *and* still
dispatchable via `@` / the Task tool.

**Tab, not `@`, for a conversation.** Tab replaces the agent you are talking to while keeping the
whole message history, so the new agent sees everything that came before. `@agent-name` dispatches
a *one-shot subagent* in a child session: one prompt in, one message out, no way for it to ask you
a question and hear the answer. The Product Owner's discovery interview and the Tech Lead's
orchestration of a whole story therefore only work via **Tab**; `@` is for bounded, self-contained
work such as a review or an audit. `mode: all` rather than `primary` is deliberate for both, so the
Tech Lead can still dispatch the Product Owner for stage 0 of its own pipeline.

Intended flow for a new piece of work: **Tab to Product Owner** and be interviewed until
requirements are frozen and stories are filed → **Tab to Tech Lead**, which sees the whole
interview, to run delivery → **Tab back to `build`** for tooling, configuration and meta-work.

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
