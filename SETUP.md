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

> **The figures above were honoured on the fourth firing of the trigger, and
> only because a human asked.** That is the fact worth keeping. A re-check
> conditioned on "whenever a pin moves" failed to survive a pin moving three
> times: twice while no JavaScript runtime was on `PATH` (`opencode-supermemory`
> 2.0.10 → 2.0.11 and `opencode-goal-plugin` 0.6.5 → 0.6.7), and once with no
> such excuse, when those two went 2.0.11 → 2.0.12 and 0.6.7 → 0.8.1 alongside
> `@tarquinen/opencode-dcp` 3.1.14 → 3.1.15 — an upgrade that *edited this very
> paragraph to record the trigger being unhonoured* without honouring it. The
> runtime excuse is gone for good: Node is a prerequisite of this project
> (Graphify's CLI needs it), so `node` and `npm` are on `PATH`. Read the history
> as evidence about the trigger, not about the risk. **If this audit matters it
> needs a CI job, not another sentence here** — the one thing four firings have
> established is that prose does not run.
>
> **To reproduce the measurement**, generate a *separate throwaway* manifest
> whose `dependencies` are the six exact specs from the `plugin` array of
> `.opencode/opencode.json`, install it under `.tmp/` with `--ignore-scripts`,
> then run `npm audit` and `npm audit signatures` against it. Do **not**
> repurpose `.opencode/package.json`: it exists again on disk (untracked and
> gitignored) but declares exactly one dependency, `@opencode-ai/plugin`, the
> typings package used to author `.opencode/plugins/graphify.js`. Auditing
> `.opencode/` therefore audits the local authoring toolchain rather than the
> plugins that load, and the same goes for the 61 MB `.opencode/node_modules`
> tree beside it, which vendors no plugin packages at all. OpenCode resolves
> plugins through `Npm.add()` into `~/.cache/opencode/packages/<spec>/`, so
> **the authoritative plugin versions are the exact pins in the `plugin` array
> of `.opencode/opencode.json`, and nowhere else.** `npm audit signatures`
> needs a real install — with only a lockfile it exits "found no dependencies
> to audit that were installed from a supported registry".
>
> **What that measurement does and does not cover.** `npm audit` answers "are
> there *published advisories* against these versions". It does not answer "has
> one of these releases been backdoored", which is the failure mode that
> actually matters for plugins running unsandboxed inside OpenCode with sight of
> every message and file. Three checks were run alongside it on 2026-08-20 and
> are the ones to repeat on any future bump: **maintainer continuity** — the
> same npm account published the old and new version of all three bumped
> packages (`tarquinen`, `dhravya`, `williamricchiuti`), an account handoff
> being the loudest single compromise signal; **registry signatures** — 152 of
> 152 verified; and **SLSA provenance attestations** — 51 packages carry them,
> including `@tarquinen/opencode-dcp@3.1.15` and `opencode-supermemory@2.0.12`,
> but **`opencode-goal-plugin@0.8.1` carries none**. That last one is the
> weakest link in the roster and deserves naming: hand-published by a single
> maintainer, six releases in ten days, and the most privileged plugin we load
> (it spawns agents, writes state and reads whole sessions). Nothing suspicious
> was found in it; there is simply less to verify against. And provenance only
> ever proves a tarball came from the named repository's CI — never that the
> repository's code is benign. One gap is unmeasured: the audit installs with
> `--ignore-scripts`, and whether OpenCode's own `Npm.add()` path runs lifecycle
> scripts has not been verified.
>
> Drop the residual entry entirely once upstream patches land.
