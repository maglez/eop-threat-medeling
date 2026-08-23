# ADR-055: k6 Performance Check in CI

**Status:** Accepted  
**Date:** 2026-08-23  
**Deciders:** @architecture-guardian, @tech-lead  

## Context

Story EOP-155 adds a k6-based performance regression canary to the CI pipeline. The existing k6 setup runs locally against a Colima-backed stack with a curated baseline (p95 9.856ms, max 37.674ms, p99 16.029ms, error rate 0.00%) that provides ~20× headroom to the 200ms SLO threshold. k6 exits 0 even when it cannot write stats to InfluxDB, so the exit code alone is not a trustworthy signal.

The CI workflow (`.github/workflows/ci.yml`) has five jobs: `build`, `ui`, `image`, `supply-chain`, and `dependency-cve`. Only `build` is a required status check on `main`. The `image` job already runs a smoke test that starts the full stack via Docker Compose, hits endpoints through Caddy, and tears down.

## Decision

### 1. Where the check lives

The k6 performance check lives **inside the `image` job**, as a step after the existing smoke test. This leverages the already-running stack (PostgreSQL + application + Caddy) without requiring a separate job to start and stop Compose. The `image` job already has Docker and buildx available, which is needed to run the k6 container.

### 2. How k6 is installed

k6 runs from the **official `grafana/k6` Docker image, pinned by SHA-256 digest**. This avoids adding a third-party GitHub Action (which would be the first unaudited external Action in the pipeline), avoids an unpinned apt source, and reuses the Docker infrastructure already present in the `image` job.

The image runs with `--network host` to reach the host's Caddy on `127.0.0.1:443` (the same path the existing smoke test uses). This must be verified during implementation.

### 3. Threshold behaviour on breach

**The check FAILS hard on any threshold breach.** The PO's recommendation of "WARN first, promote to FAIL after ≥10 builds" was considered and rejected: a warning annotation on a non-required job is invisible to the merge gate, and the job would exist without actually gating anything.

However, the thresholds are **relaxed for the CI environment** to account for 2-vCPU runner jitter:

| Metric | CI Threshold | Rationale |
|--------|--------------|-----------|
| `http_req_failed` | `rate < 0.001` (abortOnFail: true) | Functional errors are never runner noise — a broken endpoint, failed migration, or Caddy misroute is a real regression |
| `http_req_duration` | `p(95) < 500` (abortOnFail: true) | 500ms is 2.5× the SLO (200ms) and 50× the observed baseline (9.856ms), immune to 2-vCPU jitter while still catching gross regressions (N+1 queries, missing indexes, 50× slowdown) |
| `http_req_duration` | `max < 2000` (abortOnFail: false) | Catches extreme outliers without failing on isolated spikes |

The SLO thresholds (200ms / 1000ms) remain in `test/k6/config/options.js` for local development. A separate CI-specific threshold module is exported from `test/k6/config/options-ci.js` (or selected by environment variable), so the two configurations diverge cleanly.

### 4. Required status check

The k6 check is **NOT a required status check on `main`** when it lands. It runs as a step inside `image`, which is already an ordinary (non-required) job. A hard `exit 1` on threshold breach will fail the job and show red in the PR, providing signal without blocking merges.

**Promoting to a required status check is a future, manual step** that requires the repository owner to configure branch protection settings in GitHub. The ADR records this as an outstanding action, not something the YAML in this repository can self-effect.

### 5. Where results go

Results are uploaded as a **GitHub Actions workflow artifact** containing:
- The k6 JSON output (`*-metrics.json`)
- The k6 summary export (`*-summary.json`)
- Any k6 logs

Results are **NOT** appended to `docs/performance/TRENDS.md` — that file tracks local development baselines and is maintained manually. The CI artifact is for debugging regressions, not for long-term trend analysis.

## Consequences

- **Positive:** A functional regression (non-2xx response) fails the CI build loudly. A latency regression large enough to breach the relaxed CI budget also fails. Both provide actionable signal without blocking merges.
- **Positive:** No new third-party Action is introduced; k6 runs from a digest-pinned container.
- **Positive:** The check reuses the already-running Compose stack from the smoke test, adding minimal wall-clock time.
- **Negative:** The k6 container must reach the host's Caddy via `--network host`. This path must be verified during implementation — if it does not work, the architecture may need to change (e.g., run k6 outside Docker, or use a sidecar container).
- **Negative:** The check is not a required status check, so it does not block merges. This is the intended starting state; promotion to required is a separate manual step.
- **Outstanding:** The repository owner must configure the k6 step (once implemented) as a required status check in GitHub branch protection settings if that is the desired end state.

## Amendments

**2026-08-23 — five findings from implementation, all verified empirically:**

### 1. The `--network host` path works

The ADR flagged this as needing verification: "if it does not work, the architecture may need to change". A throwaway one-VU probe was run as:

```
docker run --rm --network host -v "$PWD/.tmp:/scripts:ro" \
  -e BASE_URL=https://localhost grafana/k6:latest run \
  --insecure-skip-tls-verify /scripts/k6-net-probe.js
```

against the live local stack (Caddy publishing `127.0.0.1:443->8080`). It returned status=200, body=OK, 2/2 checks, `http_req_failed 0.00%`, `http_req_duration` 14.49ms. Colima runs Docker inside a Linux VM, so the mechanism exercised — a container in the host network namespace reaching a loopback-published port — is identical to a Linux GitHub runner. The full 40s `SMOKE_STAGES` run was subsequently executed the same way with the real workflow step text and passed. The verification dependency is **discharged**.

### 2. Two digest-pinning traps

The authoritative source is `docker buildx imagetools inspect grafana/k6:<version>`, and the value to take is the top-level `Digest:`.

- The tag is **`2.2.0`, not `v2.2.0`** — `docker manifest inspect grafana/k6:v2.2.0` fails outright.
- The image is an OCI image **index** (`application/vnd.oci.image.index.v1+json`) spanning `linux/amd64`, `linux/arm64` and two `unknown/unknown` buildx **attestation manifests** (provenance + SBOM — a genuine supply-chain positive worth citing, given this ADR chose the container specifically to avoid an unaudited third-party Action). The pin in use is the **index** digest `sha256:9bd01d6941fca969cb61bb57d2da5ee9b385fe2aa8881df3798c196564d6ace6`. Pinning a per-platform sub-manifest instead (amd64 `sha256:a070982921f37e1b891f8ed9fb2b507520c83228614c14640f7e28f635f4281b`, arm64 `sha256:ea746c18a0af5530f5501dbe50d2cda34a37376639c524ca3172da61394869ef`) would break `ubuntu-latest` with "no matching manifest for linux/amd64".
- **Never derive the pin from `docker inspect` on a developer machine.** On the arm64 Mac, `docker inspect --format='{{index .RepoDigests 0}}'` returned `sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec` for `:latest` — matching neither the 2.2.0 index nor either 2.2.0 platform manifest.

### 3. Accepted k6 version skew

The CI container is k6 **2.2.0** (`go1.26.5`); the developer Mac's brew k6 is **2.1.0**. CI and local therefore run different k6 versions. This is accepted rather than fixed — the pin buys CI reproducibility, which is the point; forcing the dev Mac to match would mean pinning brew, and local runs are exploratory not gating. Note it as a thing to check first if CI and local results ever diverge inexplicably.

### 4. `--user "$(id -u):$(id -g)"` over world-writable mounts

The container's default user is uid/gid **12345** (`k6`), verified by `docker run --entrypoint id`. The first implementation mounted `test/k6` read-write and asked uid 12345 to write into a directory the runner had created 0755 — permission denied, which would have failed every CI build. Two fixes were possible: `chmod 0777` the results directory, or run the container as the runner's own uid/gid. The latter was adopted and verified working with a default-0755 directory, so `/scripts` is mounted **read-only** and a separate writable `/results` mount receives the output. This is least-privilege per `.opencode/rules/security.md` — no world-writable directory for an auditor to flag.

### 5. The threshold gate asserts on the exported JSON and is negative-tested

The ADR required assertion on the JSON rather than the exit code alone. The design and its evidence:

- The exported summary's shape was verified empirically, not assumed: top-level keys are exactly `["metrics","root_group"]`; `.metrics.http_req_duration.thresholds` = `{"p(95) < 500": false, "max < 2000": false}`; `.metrics.http_req_failed.thresholds` = `{"rate < 0.001": false}`. Polarity is inverted from the naive reading — **`false` means NOT breached**.
- `http_req_failed` also exports `"passes": 0, "fails": 221, "value": 0` on a fully healthy run. That inverted-looking Rate metric is a trap; the real error rate is `value`. The gate deliberately reads only `.thresholds`.
- Threshold strings are **not** hardcoded in the jq. Hardcoding `.thresholds."p(95) < 500"` was rejected because `jq -r` emits `null` for a missing key and `[[ "null" == "true" ]]` is false, so editing the threshold text in `options-ci.js` would have silently disarmed the gate forever. Instead every threshold in the document is flattened generically and a declared `EXPECTED_THRESHOLD_COUNT=3`, commented as tied to `options-ci.js`, is compared against the count found. Consequence: a threshold **rename** is harmless, while **addition or removal** fails loudly — the intended split.
- k6's own exit code is captured separately (`k6_exit=0; docker run … || k6_exit=$?`) rather than allowing `set -e` to abort before the assertion runs, giving two genuinely independent signals. This matters because ADR-016's InfluxDB failure is precisely a case where k6 logs errors on every flush yet exits 0.
- The gate was **negative-tested in ten cases** by extracting the real step text out of the YAML programmatically and driving it against fixtures derived from a genuine summary export: missing file, zero-byte file, malformed JSON, a JSON array instead of an object, one threshold deleted, a fourth threshold added, a threshold set `true` (breach), a threshold set to the non-boolean `"maybe"`, a healthy summary with k6 exit 99, and a healthy summary with exit 0. The first nine exit 1 with a distinct diagnostic; only the last exits 0. The gate is proven unable to silently no-op.

### Observed CI-equivalent numbers (not a baseline)

The real 40s run through the workflow step: 663/663 checks passed, `http_req_failed 0.00% (0 of 221)`, avg 3.34ms, med 1.86ms, p95 **10.51ms**, p99 25.06ms, max 39.53ms, 221 iterations at 5.4947/s, vus_max 10. Against the CI budget this is ~48× headroom on p95 (10.51 vs 500) and ~50× on max (39.53 vs 2000), and it is consistent with the 9.856ms `TRENDS.md` baseline. **These numbers were measured on a developer Mac via Colima and are not written to `docs/performance/TRENDS.md`** — mixing them into the curated baseline would repeat the error that forced the 2026-08-05 baseline reset. They are recorded here only as evidence that the CI gate has substantial headroom and that the `--network host` path delivers the expected performance.

**2026-08-23 (later the same day) — a sixth finding, from the first real CI run:**

### 6. Tear down by project label, not by file

The first CI run on PR #122 (run `32644143043`) passed the k6 step on a real GitHub runner — 221 iterations, 0 interrupted, all three CI thresholds green, a 30,349-byte `k6-results.zip` artifact — and then failed the *next* step:

```
error while interpolating services.postgres.environment.POSTGRES_DB:
required variable POSTGRES_DB is missing a value: POSTGRES_DB must be set in .env
Process completed with exit code 1
```

`compose.app.yml` declares `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` with the fail-hard `${VAR:?}` form, so passing `-f compose.app.yml` makes Compose refuse to **parse** the file when they are unset — even for `down`, which needs none of their values. Those three exist only as step-level `env:` on the `Smoke test` step; this story moved teardown out of that step into its own `if: always()` step, which carried no `env:`, and so lost them.

The fix resolves the stack by its project label instead, with no `-f` at all:

```yaml
- name: Tear down stack
  if: always()
  run: docker compose -p eop-app down --volumes
```

`compose.app.yml` declares `name: eop-app`, so the label is canonical. `docker compose -p eop-app --dry-run down --volumes`, run from the repository root where `docker-compose.yml` also lives, resolved only the app stack — containers `eop-postgres`, `eop-caddy`, `eop-app`, network `eop-app_default`, and volumes `eop-app_eop_postgres_data` and `eop-app_eop_caddy_data` — and left the separate `eop-monitoring` stack untouched. CI run `32644675531` then passed the `image` job on a real runner.

Re-adding an `env:` block was considered and rejected. It would hand PostgreSQL credentials to a step that never authenticates to PostgreSQL, and would have to stay in sync with the `Smoke test` block or silently break again. Resolving by label needs no credentials at all — least privilege by construction rather than by careful scoping (`.opencode/rules/security.md`).

### The lesson, and the gate deliberately not built

**Any `if: always()` cleanup step that passes `-f <file>` must be audited for `${VAR:?}` interpolation in that file.** That is a reviewer checklist item, not a build gate. A `docker compose -f compose.app.yml config --quiet` probe with those three variables deliberately unset would catch it, and needs no Docker daemon because `config` only parses — but the return was judged too low for a permanent gate while `compose.app.yml` is the only file here using `${VAR:?}`. Revisit if a second one appears.

Recorded honestly: **all five Definition-of-Done gates and the Tech Lead missed this defect** in the round before it reached a runner. It was unreachable locally, because local verification executed the k6 step's script directly and never the surrounding Compose lifecycle. The generalisable point is about the limits of the local loop rather than about any one reviewer — a step that only ever runs in CI is only ever proven in CI.

## Related

- ADR-016 (Colima as local container runtime)
- ADR-017 (Front-end delivery via Caddy on a single origin)
- `.github/workflows/ci.yml` (the file this ADR modifies)
- `test/k6/config/options.js` (existing thresholds)
- `test/k6/config/options-ci.js` (the relaxed CI thresholds this ADR mandates)
- `docs/performance/TRENDS.md` (local baseline, not CI results)
