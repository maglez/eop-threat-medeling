# Elevation of Privilege - Setup Guide

## Prerequisites
- Java 21 (Maven Wrapper included — no global Maven needed)
- **Colima + the Docker CLI plugins** (`brew install colima docker docker-compose docker-buildx`) — required to run the application stack, the monitoring stack or the k6 load test. Four formulae: the `docker` formula is a client only and ships **neither** Compose **nor** Buildx. **Do not install Docker Desktop** — it needs administrator rights this machine does not grant, and its licence requires a paid subscription for commercial use above an organisation-size threshold. See [ADR-016](docs/adr/ADR-016-local-container-runtime.md)
- **k6** (`brew install k6`) — required by `test/k6/run.sh`
- **direnv** — required to load `.env` into the Spring app (see below)
- **Node.js 22.12+** (`brew install node`) — required twice over. The Graphify knowledge graph needs Node 20 or later, and below that the `graphify` MCP server silently fails to start and no `graphify_*` tools appear. The `ui/` front end declares `engines.node >= 22.12` and CI builds it on Node 22, so **22.12** is the floor for this repository. The patch-level floor is not decoration: it is Vite 7's own requirement (`^20.19.0 || >=22.12.0`), so Node 22.0–22.11 satisfies a bare "22" while violating Vite's own range — and npm will not stop you, because `engine-strict` is unset and there is no `.npmrc` at the repository root or in `ui/`, so it prints an `EBADENGINE` warning and installs anyway, leaving the mismatch to surface later as a confusing Vite failure. `actions/setup-node` with `node-version: 22` resolves to the latest 22.x and is comfortably above it
- **uv** (`brew install uv`) — required by the Atlassian MCP server, launched as `uvx mcp-atlassian`. Without `uvx` on `PATH` it silently fails and no `atlassian_jira_*` tools appear

### Per-clone setup that cannot be committed
Five steps live outside version control. **All five fail silently or misleadingly**,
so verify each one:

```bash
# Install the pinned Graphify CLI (see Blueprint §5.2 for why --ignore-scripts)
cd tools/graphify && npm install --ignore-scripts && cd -
graphify --version            # must print 0.17.1

# Install the front-end dependencies
cd ui && npm install && cd -
cd ui && npm run verify && cd -   # typecheck, lint, test, build — all four must pass

# Activate the committed git hooks — enforces the [EOP-NNN] commit prefix
git config core.hooksPath .githooks
git config --get core.hooksPath   # must print .githooks

# Start the container runtime (needed after every reboot — see ADR-016)
colima start --vm-type=vz --cpu 4 --memory 6 --disk 60
docker compose version        # must print a version, not "unknown command"
docker info | grep -i 'Server Version'   # must succeed, not a socket error

# Point the Docker CLI at the Homebrew plugin directory, if it does not already
grep -q cliPluginsExtraDirs ~/.docker/config.json || \
  echo 'add "cliPluginsExtraDirs": ["/opt/homebrew/lib/docker/cli-plugins"] to ~/.docker/config.json'
```

> **If `docker compose` reports `unknown command`, do not conclude Compose is
> missing.** A previous Docker Desktop install can leave dangling plugin symlinks
> in `~/.docker/cli-plugins/` pointing into an unmounted disk image; the CLI finds
> them, cannot execute them, and reports the command as unknown. Delete any
> symlink there whose target does not exist. Also remove `credsStore` and
> `currentContext` from `~/.docker/config.json` if they name `desktop`.

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

> **An `.env` predating the containerised stack will be missing `POSTGRES_DB`,
> `POSTGRES_USER` and `POSTGRES_PASSWORD`.** `compose.app.yml` declares those with
> required-variable syntax, so `docker compose -f compose.app.yml up -d` fails
> loudly rather than starting a database with no password. That is the good case —
> add them and retry.
>
> **direnv exports `.env` at directory entry, so editing it does not change an
> already-running shell.** After changing a variable, open a new shell or pass the
> value inline. Two separate diagnoses were wasted on this.

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

## Security Notes — Accepted Risks (reviewed 2026-08-20)

The following items were reviewed and **accepted**; do not re-flag them in
future audits without new evidence.

### Production hardening applied
- `application-prod.yml` disables springdoc (`/v3/api-docs`, `/swagger-ui.html`).
  They remain enabled in the default profile for local development.
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
- **`npm audit` residual: 0 critical / 0 high / 0 moderate / 4 low** —
  *measured 2026-08-20 against the six exact pins then current, and it
  supersedes a 2026-07-27 snapshot of 5 high / 4 low.* Read the direction of
  that change before reading the numbers: the highs were retired **by** the
  plugin upgrade, not in spite of it, so on this evidence holding the old pins
  was the more exposed position. All four remaining lows are one chain —
  `@babel/core` "Arbitrary File Read via `sourceMappingURL` Comment", reached
  through `@tarquinen/opencode-dcp` → `@opentui/solid` →
  `@opencode-ai/plugin`. Accepted because:
  1. There is no forward fix. npm's only `fixAvailable` is a **downgrade** to
     `@tarquinen/opencode-dcp@3.1.12`, flagged `isSemVerMajor`, which means
     the sole automated remedy is to give up DCP and its OpenTUI runtime.
  2. Exploitation requires a crafted `sourceMappingURL` comment in source
     Babel is asked to transform. Nothing here feeds untrusted input to that
     chain: it exists to build the DCP TUI from plugin source.
  3. It is a *build-time* dependency of a dev tool, not a runtime dependency
     of the shipped Java application, which has no npm dependencies at all.

> **This check now runs itself: `tools/supply-chain/audit-plugins.sh`, wired
> into CI as the non-required `supply-chain` job.** Reproduce the figures above
> by running that script; it needs `node`, `npm` and `python3` and works inside
> `.tmp/supply-chain`. It fails on four conditions — a maintainer account
> change, a provenance change in *either* direction, a plugin spec that has
> lost its exact version, or a high/critical advisory. Low and moderate
> findings deliberately do not fail it, because the residual accepted above is
> exactly four lows and a job that goes red on a known accepted finding gets
> ignored. The baseline it compares against is
> `tools/supply-chain/expected-plugins.json`, which is a **tripwire, not a
> policy**: it records what was true when a human last looked, so that a
> *change* becomes loud. Update it deliberately, in the same commit that moves
> a pin, and never to make a red job green.
>
> **Automating it was the actual finding of this exercise.** The trigger that
> preceded the script was a sentence saying "re-check whenever a pin moves",
> and it fired four times and was honoured once — the once being when a human
> asked directly. Twice it was missed while no JavaScript runtime was on `PATH`
> (`opencode-supermemory` 2.0.10 → 2.0.11 and `opencode-goal-plugin`
> 0.6.5 → 0.6.7), and once with no such excuse, when those two went
> 2.0.11 → 2.0.12 and 0.6.7 → 0.8.1 alongside `@tarquinen/opencode-dcp`
> 3.1.14 → 3.1.15 — an upgrade that *edited this very paragraph to record the
> trigger as unhonoured* without honouring it. Read that as evidence about
> prose, not about the risk. Prose does not run.
>
> The `supply-chain` job carries a **weekly cron** as well as running on push
> and pull request, and that is the point rather than a nicety: every other job
> in the workflow is a function of a commit, but an advisory is published by
> someone else against code that has not changed, so a check that only fires on
> push cannot observe one arriving. It is deliberately **not** a required
> status check — `build` stays about the code, and a fresh transitive advisory
> is something to know about rather than a reason to block an unrelated merge.
>
> **What the measurement covers, and what it cannot.** `npm audit` answers "is
> there a *published advisory* against these versions". It does not answer "was
> one of these releases backdoored", which is the failure mode that actually
> matters for plugins running in-process and unsandboxed with sight of every
> message and file. Three further checks address that as far as anything can,
> and all three are in the script: **maintainer continuity** — the loudest
> single compromise signal is an account handoff, and every pinned package's
> maintainer set is unchanged; **registry signatures** — 152 of 152 verified;
> and **SLSA provenance attestations** — 51 packages carry them, but only
> **three of the six plugins** do (`@tarquinen/opencode-dcp`,
> `opencode-supermemory`, `@nick-vi/opencode-type-inject`). The three without
> are `opencode-vibeguard`, `opencode-scheduler` and `opencode-goal-plugin`.
> Correcting an earlier version of this note: goal-plugin is **not** uniquely
> unattested, and the two it shares that with are uncomfortable company —
> `opencode-vibeguard` is the secret-redaction plugin, so the component asked
> to keep credentials out of prompts is itself among the least verifiable, and
> `opencode-scheduler` writes launchd/systemd units and therefore has the most
> persistent reach outside the editor process. State this as *less to verify
> against*, never as evidence of a problem: nothing suspicious has been found
> in any of them. And provenance only ever proves a tarball came from the named
> repository's CI — never that the repository's code is benign.
>
> **Plugin installation does not run npm lifecycle scripts. Verified, not
> assumed.** This closes a gap an earlier version of this note left open.
> OpenCode does not shell out to a package manager at all: `Npm.add()` in
> `packages/core/src/npm.ts` (read at tag `v1.18.19`, matching the installed
> binary) imports `@npmcli/arborist` in-process and constructs it with
> `ignoreScripts: true` placed *after* the spread of user npm config, so the
> flag cannot be overridden by an `.npmrc`; arborist's `rebuild.js` gates
> `preinstall`, `prepare`, `install` and `postinstall` on exactly that flag,
> and it is the only path reaching `@npmcli/run-script`. Three caveats keep
> this honest. `binLinks: true` is still passed, so `node_modules/.bin`
> symlinks *are* created — "no scripts" is not "no executables placed". The
> guarantee rests on `@npmcli/arborist` being pinned to exactly `9.4.0`, which
> reads the flag from its constructor options rather than per-call ones; an
> arborist that merged per-call options would silently re-admit the `.npmrc`
> value that OpenCode already forwards. And the scope is the *install* step
> only — the plugin's own module code executes at import time by design, which
> is a far larger surface than any postinstall hook. That is why the checks
> above are about *who published this* rather than about install-time
> execution.
>
> Drop the residual entry entirely once upstream patches land.
