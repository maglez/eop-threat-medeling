# Local Development Guide

## Prerequisites
- **Java 21** (JDK) — Install via [Homebrew](https://brew.sh): `brew install openjdk@21`
- **Node.js 20+** — for the planned React front-end only. Install via [Homebrew](https://brew.sh): `brew install node`. The OpenCode plugins in `.opencode/` do **not** need it — OpenCode ships an embedded runtime and installs their `node_modules` itself.
- **direnv** — [install guide](https://direnv.net/docs/installation.html)
- **uv** — **required** for the Atlassian MCP server, which OpenCode launches as `uvx mcp-atlassian`. Install via [Homebrew](https://brew.sh): `brew install uv`. Without `uvx` on `PATH` the server silently fails to start and no `atlassian_jira_*` tools appear.
- **GitHub PAT** with `repo` scope — used by both the read-only GitHub MCP server and the `gh` CLI
- **Docker Desktop** — for the monitoring stack (InfluxDB + Grafana). [Download here](https://www.docker.com/products/docker-desktop/) or `brew install --cask docker`
- **k6** — for load testing: `brew install k6`

## Setup

```bash
# 1. Clone
git clone git@github.com:maglez/eop-threat-medeling.git
cd eop-threat-medeling

# 2. Allow direnv to load .env vars
direnv allow

# 3. Verify env vars loaded
echo $GITHUB_TOKEN

# 4. Build and test
./mvnw compile
./mvnw test

# 5. Run the application
./mvnw spring-boot:run
# Verify: curl http://localhost:8080/health
```

### OpenCode shell wrapper (recommended, one-off per developer)

OpenCode must always be launched from the repository root — see the "OpenCode Agent
System" section of `AGENTS.md` for why. Add this to your `~/.zshrc` so it happens
automatically. The `cd` runs in a subshell, so your own working directory is
unchanged, and invocations outside a git repository pass straight through.

```zsh
opencode() {
  local root
  root=$(git rev-parse --show-toplevel 2>/dev/null)
  if [[ -n "$root" && "$PWD" != "$root" ]]; then
    print -u2 "opencode: launching from repository root ($root)"
    ( cd "$root" && command opencode "$@" )
  else
    command opencode "$@"
  fi
}
```

## Environment Variables

| Variable | Required | Source | Purpose |
|---|---|---|---|
| `JAVA_HOME` | Yes | JDK 21 install | Path to JDK 21 (e.g. `/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`) |
| `GITHUB_TOKEN` | Yes | GitHub PAT | GitHub MCP auth (read-only) and `gh` CLI. Writes to `main` go via PR — branch protection blocks direct pushes for everyone, admins included |
| `JIRA_URL` | For Jira | Atlassian | Jira instance URL |
| `JIRA_USERNAME` | For Jira | Atlassian | Jira bot email |
| `JIRA_API_TOKEN` | For Jira | Atlassian | Jira API auth |
| `DATASOURCE_URL` | For prod | PostgreSQL | JDBC URL (default: `jdbc:h2:mem:eop` for dev) |
| `DATASOURCE_USER` | For prod | PostgreSQL | DB user (default: `sa` for dev) |
| `DATASOURCE_PASSWORD` | For prod | PostgreSQL | DB password (default: empty for dev) |

All vars go in `.env` (gitignored).

### AI provider auth

The AI provider needs **no environment variables**. OpenCode Zen is a built-in
provider (id `opencode`); its key lives in `~/.local/share/opencode/auth.json`
and is set once by running `/connect` inside the OpenCode TUI. Verify with:

```bash
opencode models | grep '^opencode/'
```

See Blueprint §3.4 for the model allocation and endpoints.

## Database

### Development (H2)

The default `application.yml` profile uses an **H2 in-memory database** with zero setup required. Liquibase runs automatically on application startup — changelogs are applied in order.

- H2 console is available at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:eop`, user: `sa`, no password)
- Hibernate `ddl-auto=validate` — the schema is entirely managed by Liquibase

### Production (PostgreSQL)

Activate the `prod` profile to connect to PostgreSQL:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

PostgreSQL connection details are read from `DATASOURCE_URL`, `DATASOURCE_USER`, and `DATASOURCE_PASSWORD` env vars (set in `.env`).

### Adding a migration

1. Create a new file in `src/main/resources/db/changelog/changes/YYYY-MM-DD--<description>.xml`
2. Add one or more `<changeSet>` blocks with `<rollback>` instructions
3. Run `./mvnw spring-boot:run` — Liquibase applies the new changeset automatically
4. To preview the SQL: `./mvnw liquibase:updateSQL`

See `.opencode/rules/database.md` and ADR-008 for full conventions.

## Load Testing with k6

The project uses **k6** for load and smoke testing, with results streamed to **InfluxDB** and visualised in **Grafana** for historical trend analysis.

### Architecture

```
k6 run ──► InfluxDB (:8086) ──► Grafana (:3000)
           (database: k6)       (provisioned dashboard)
```

### Quick smoke test (standalone, no Docker needed)

```bash
# App must be running
k6 run test/k6/health-check.js
```

### Full monitoring stack

```bash
# Terminal 1: Start monitoring
docker compose up -d

# Terminal 2: Start the app
./mvnw spring-boot:run

# Terminal 3: Run load test (pushes to InfluxDB for Grafana)
test/k6/run.sh

# Open Grafana dashboard
open http://localhost:3000  # Dashboard → k6 Load Testing
```

Each subsequent run adds data to InfluxDB — the Grafana dashboard accumulates history so you can compare "today" vs "last week" vs "all time."

### Test profiles

| Profile | Purpose | Duration | Peak VUs |
|---|---|---|---|
| `SMOKE_STAGES` | Quick health check | 40s | 10 |
| `LOAD_STAGES` | Sustained load | 5m | 100 |
| `STRESS_STAGES` | Find breaking point | 9m | 400 |

### SLOs

| Metric | Threshold | Action on breach |
|---|---|---|
| p95 latency | < 200ms | Test aborts (if `abortOnFail: true`) |
| max latency | < 1000ms | Warning |
| error rate | < 0.1% | Test aborts |

See `.opencode/rules/performance-testing.md` for full conventions and `test/k6/config/options.js` for thresholds.

## Front-End (React + TypeScript) — NOT YET SCAFFOLDED

> **Status: planned, not implemented.** There is no `ui/` directory in the
> repository yet. ADR-009 selects React + TypeScript + Vite with GOV.UK Design
> System styling, but no code has been written. The commands below describe the
> intended layout and will not work until the front-end is scaffolded.

### Planned quick start

```bash
cd ui
npm install
npm run dev
# Opens at http://localhost:5173 — proxies /api/* to Spring Boot on :8080
```

### Planned development workflow

```
Terminal 1: ./mvnw spring-boot:run     # API on :8080
Terminal 2: cd ui && npm run dev        # Front-end on :5173
```

## Common Commands

| Command | Purpose |
|---|---|
| `./mvnw compile` | Fast compile check |
| `./mvnw test` | Run all tests |
| `./mvnw verify` | Full verification (including integration tests) |
| `./mvnw spring-boot:run` | Start application on port 8080 |
| `./mvnw clean` | Clean build artifacts |

Front-end commands (`npm run dev`, `npm run build`, `npm test` in `ui/`) are not
available yet — see the front-end section above.

## Troubleshooting

- **`direnv: error .envrc is blocked`** — Run `direnv allow`
- **`Error: Missing authorization header` from the AI provider** — Zen auth is not
  in `.env`. Re-run `/connect` in the OpenCode TUI to refresh
  `~/.local/share/opencode/auth.json`
- **`Model not found: <name>/.`** — the model ref is missing its provider prefix
  or the model was retired. Refs must be fully qualified as `opencode/<id>`;
  check the live list with `opencode models | grep '^opencode/'`
- **Java version mismatch** — Run `java --version` and ensure it's 21. Install via Homebrew: `brew install openjdk@21`, set `JAVA_HOME` in `.env`
- **No `atlassian_jira_*` tools available** — `uvx` is not on `PATH`, so
  `uvx mcp-atlassian` never starts. Run `brew install uv`, then restart OpenCode.
  MCP servers are only registered at session start
- **Dozens of unexpected agents in the Tab cycle** (named after `node_modules`
  packages) — OpenCode was launched with a working directory inside `.opencode/`,
  so its plugin tree landed inside the recursive `.opencode/agents/` scan. Launch
  from the repository root instead; the `opencode()` wrapper in `~/.zshrc` does
  this automatically. The phantoms stay registered until you restart OpenCode
- **`BadResource: FileSystem.readFile (.../.opencode/agents/.opencode/opencode.json)`**
  — you launched OpenCode from inside `.opencode/agents/`. This is the sentinel
  guard working as intended. `cd` to the repository root and retry
- **`ERROR goal persistence is already owned by pid <n>`** — a second OpenCode
  instance was started from this directory and lost the race for the goal
  plugin's persistence lease. Only one instance per `stateFilePath` may hold it.
  Either close the other instance, or give this one its own state root:
  `OPENCODE_GOAL_STATE_PATH=/tmp/goals-2/state.json opencode`. Fixed upstream in
  goal-plugin `0.6.8`, which is not published to npm yet — see Blueprint §12.8
- **`/goal <subcommand>` is ignored and no `*_goal` tools are listed** — the goal
  plugin is running a release older than `0.6.7`, whose tools never register on
  OpenCode 1.18.x. Check the pin in `.opencode/opencode.json` and restart
- **`~/.cache/opencode/packages/` has grown to hundreds of MB** — expected, and
  safe to prune. OpenCode keys that cache on the literal plugin spec, and rewrites
  a bare name to `<name>@latest`, so every plugin can accumulate three directories:
  `<name>`, `<name>@latest`, and each pinned version it has ever had. Only the six
  specs currently listed in `.opencode/opencode.json` are referenced; the rest are
  orphans (this cleanup reclaimed 651 MB of 983 MB). Delete the unreferenced
  directories and restart — OpenCode reinstalls anything it still needs. Note the
  cache is **user-global**: removing a `<name>@latest` directory makes any *other*
  OpenCode project that leaves that plugin unpinned re-resolve `latest`, which can
  move it to a newer version
