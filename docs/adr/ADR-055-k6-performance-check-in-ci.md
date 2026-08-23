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

## Related

- ADR-016 (Colima as local container runtime)
- ADR-017 (Front-end delivery via Caddy on a single origin)
- `.github/workflows/ci.yml` (the file this ADR modifies)
- `test/k6/config/options.js` (existing thresholds)
- `docs/performance/TRENDS.md` (local baseline, not CI results)