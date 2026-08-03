# Elevation of Privilege - Setup Guide

## Prerequisites
- Java 21 (Maven Wrapper included — no global Maven needed)
- Docker + Docker Compose (for monitoring stack)
- **direnv** — required to load `.env` into the Spring app (see below)
- **Node.js 20+** (`brew install node`) — required by the Graphify knowledge graph, whose CLI OpenCode launches as a separate process. Below Node 20 the `graphify` MCP server silently fails to start and no `graphify_*` tools appear
- **uv** (`brew install uv`) — required by the Atlassian MCP server, launched as `uvx mcp-atlassian`. Without `uvx` on `PATH` it silently fails and no `atlassian_jira_*` tools appear

### Per-clone setup that cannot be committed
Two steps live outside version control. Both fail **silently**, so verify each one:

```bash
# Install the pinned Graphify CLI (see Blueprint §5.2 for why --ignore-scripts)
cd tools/graphify && npm install --ignore-scripts && cd -
graphify --version            # must print 0.17.1

# Activate the committed git hooks — enforces the [EOP-NNN] commit prefix
git config core.hooksPath .githooks
git config --get core.hooksPath   # must print .githooks
```

MCP servers are registered only at session start, so restart OpenCode afterwards.

## Environment Configuration

### 1. Create `.env`
```bash
cp .env.example .env
chmod 600 .env
```
If `.env` already exists, append only missing variables:
```bash
grep -v '^#' .env.example >> .env   # then remove duplicates manually
```

> **`chmod 600 .env` is not optional.** `cp` gives the new file your umask
> default, which on macOS is `0644` — group- and world-readable. `.env` holds
> live credentials (`JIRA_API_TOKEN`, `GITHUB_TOKEN`, `SUPERMEMORY_API_KEY`,
> `AWS_BEARER_TOKEN_BEDROCK`, and the Grafana/InfluxDB admin passwords), so any
> local process under any account on the machine could read them. It sat at
> `0644` from creation until the 2026-08-02 audit caught it. For comparison,
> OpenCode's own credential store `~/.local/share/opencode/auth.json` is `0600`
> and `.opencode/goals/` is `0700` — `.env` was the outlier. Verify with
> `ls -l .env` and expect `-rw-------`.

### 2. Fill in `.env` values
You **choose** these values yourself — they seed accounts on first boot:

| Variable             | Description                              | Example               |
|----------------------|------------------------------------------|-----------------------|
| `DB_URL`             | JDBC connection URL                      | `jdbc:h2:mem:eop`     |
| `DB_USERNAME`        | Database username                        | `sa`                  |
| `DB_PASSWORD`        | Database password (blank OK for local H2)|                       |
| `GF_SECURITY_ADMIN_USER` | Grafana login username                  | `admin`               |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana login password (you choose; wrap in single quotes if it contains `$`) | |
| `INFLUXDB_URL`       | InfluxDB URL (inside Docker network)     | `http://influxdb:8086`|
| `INFLUXDB_USER`      | InfluxDB admin username (you choose)     | `eop_admin`           |
| `INFLUXDB_PASSWORD`  | InfluxDB admin password (you choose)     |                       |

### 3. Load `.env` via direnv (required for the backend)
Spring Boot does **not** read `.env` natively — the app fails fast if
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD` are unset. The repo's `.envrc` (`dotenv`)
loads them via direnv:
```bash
brew install direnv
echo 'eval "$(direnv hook zsh)"' >> ~/.zshrc   # or your shell's hook
direnv allow
```
Without direnv, export the variables manually before running the app.

## Running the Application

### Backend (from repo root)
```bash
./mvnw spring-boot:run
```

### Monitoring stack — Grafana + InfluxDB (from repo root)
`docker-compose.yml` lives at the **repo root**, not in `tools/monitoring`:
```bash
docker-compose up -d
```

**First boot / after changing any `GF_SECURITY_*` or `INFLUXDB_*` values:**
credentials are baked into volumes on first start only. Recreate them:
```bash
docker-compose down -v   # wipes grafana_data + influxdb_data
docker-compose up -d
```

### Logging into Grafana
1. Open `http://localhost:3000/login`
2. Username: `GF_SECURITY_ADMIN_USER` (default `admin`)
3. Password: your `GF_SECURITY_ADMIN_PASSWORD` value
4. Changing the password in the UI afterwards is fine — it lives in
   Grafana's internal DB from then on (`.env` is only the first-boot seed).

## Security Notes — Accepted Risks (2026-07-27 audit)

The following items were reviewed and **accepted**; do not re-flag them in
future audits without new evidence.

### Production hardening applied
- `application-prod.yml` disables springdoc (`/v3/api-docs`, `/swagger-ui.html`).
  They remain enabled in the default (dev) profile for local development.
- **Notificator plugin removed** — it shelled out to OS commands
  (`osascript`/`afplay`/`notify-send`) for desktop notifications; attack
  surface not justified by utility. See Blueprint §12.6.

### OpenCode dev tooling (`.opencode/` — not shipped, never in production)
- **`tiktoken`** (transitive via `@anthropic-ai/tokenizer`) — offline WASM
  tokenizer, makes no network calls. No drop-in replacement exists without
  forking upstream plugins.
- **`@ai-sdk/openai-compatible`** — **removed.** It existed only to back a
  custom provider block in `.opencode/opencode.json`. That block was deleted
  when the project moved to OpenCode Zen, which is a built-in provider
  (id `opencode`) requiring no SDK dependency and no endpoint env var.
- **`npm audit` residual: 5 high / 4 low** — *historical snapshot, taken
  2026-07-27 against the plugin versions current on that date.* ReDoS/DoS-class
  in `glob`/`minimatch`/`brace-expansion` via `@opentui/solid` →
  `babel-plugin-module-resolver`, plus advisories against
  `@opencode-ai/plugin`, `@tarquinen/opencode-dcp`, `@babel/core`,
  `opencode-goal-plugin`. Accepted because:
  1. No patched versions exist for the flagged packages — npm's only
     automated fix downgrades `@opencode-ai/plugin` 1.18.5 → 1.3.3 and
     peers, breaking every local plugin (Graphify, DCP, goal).
  2. Surgical `overrides` would require forcing 3–4 major-version jumps
     (`glob` 9→13) deep in the TUI build chain — same breakage risk.
  3. Exploitation requires crafted glob patterns; all patterns here come
     from plugin source code, never untrusted input.

> **The `npm audit` figures above are a 2026-07-27 snapshot and must not be
> treated as current.** They were unverifiable for a period because no JavaScript
> runtime was on `PATH` — OpenCode ships as a standalone binary with bun embedded
> privately and does not expose it. **That is no longer true:** Node is now a
> prerequisite of this project (Graphify's CLI needs it), so `node` and `npm` are
> on `PATH` and the re-check is once again runnable. The trigger fired twice
> without being honoured while the runtime was missing, when
> `opencode-supermemory` went 2.0.10 → 2.0.11 and `opencode-goal-plugin` went
> 0.6.5 → 0.6.7. What still blocks a re-run is the **manifests**:
> `.opencode/package.json` and `.opencode/package-lock.json` have been
> **removed**. `git rm --cached` untracked them (the intent was already recorded
> in `.opencode/.gitignore`, but never took effect because `.gitignore` does not
> apply to already-tracked files), and because they had been committed, git also
> deleted the working-tree copies on the next checkout past that deletion. They
> were inert for plugin loading anyway, since OpenCode resolves plugins through
> `Npm.add()` into `~/.cache/opencode/packages/<spec>/`, and they had drifted —
> declaring `^2.0.10` / `^0.6.5`, so even with npm installed an audit would have
> reported on versions that are not loaded. **The authoritative plugin versions
> are the exact pins in the `plugin` array of `.opencode/opencode.json`, and
> nowhere else.** To reinstate this audit: generate a fresh
> `.opencode/package.json` and `package-lock.json` from those exact pinned specs,
> run `npm audit` in `.opencode/`, and replace this warning with the fresh
> findings. Drop the entry entirely once upstream patches land.
