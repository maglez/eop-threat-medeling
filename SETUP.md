# Elevation of Privilege - Setup Guide

## Prerequisites
- Java 21 (Maven Wrapper included — no global Maven needed)
- Docker + Docker Compose (for monitoring stack)
- **direnv** — required to load `.env` into the Spring app (see below)

## Environment Configuration

### 1. Create `.env`
```bash
cp .env.example .env
```
If `.env` already exists, append only missing variables:
```bash
grep -v '^#' .env.example >> .env   # then remove duplicates manually
```

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
- **`npm audit` residual: 5 high / 4 low** (ReDoS/DoS-class in
  `glob`/`minimatch`/`brace-expansion` via `@opentui/solid` →
  `babel-plugin-module-resolver`, plus advisories against
  `@opencode-ai/plugin`, `@tarquinen/opencode-dcp`, `@babel/core`,
  `opencode-goal-plugin`). Accepted because:
  1. No patched versions exist for the flagged packages — npm's only
     automated fix downgrades `@opencode-ai/plugin` 1.18.5 → 1.3.3 and
     peers, breaking every local plugin (Graphify, DCP, goal).
  2. Surgical `overrides` would require forcing 3–4 major-version jumps
     (`glob` 9→13) deep in the TUI build chain — same breakage risk.
  3. Exploitation requires crafted glob patterns; all patterns here come
     from plugin source code, never untrusted input.
- **Re-check** `cd .opencode && npm audit` whenever plugin versions are
  bumped; drop this entry when upstream patches land.
