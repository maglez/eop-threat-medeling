# Performance Testing Conventions — k6 + InfluxDB + Grafana

## Authority

Load testing uses **k6**, with results streamed to **InfluxDB** and visualised via **Grafana** (provisioned dashboard). Baseline tracking follows the performance engineer agent conventions in `agents/performance-engineer.md`.

`test/k6/run.sh` streams metrics with `k6 run --out influxdb="$INFLUXDB_URL"`, defaulting to `http://localhost:8086/k6` — InfluxDB listens on `127.0.0.1:8086` (bound to loopback in `docker-compose.yml`, which is also where its version is pinned: `image: influxdb:1.8`) with database `k6`. Grafana reads that database on `:3000`. Override either endpoint with the `INFLUXDB_URL` / `BASE_URL` environment variables.

## Test Scripts

All k6 scripts live under `test/k6/`:

```
test/k6/
  health-check.js        # Smoke test — validates /health endpoint
  config/
    options.js           # Shared thresholds, stages, profiles
  run.sh                 # Helper: runs script, pushes to InfluxDB + JSON
```

## Profiles (defined in `config/options.js`)

| Profile | VUs | Duration | Purpose |
|---|---|---|---|
| `SMOKE_STAGES` | 5→10→0 | 40s | Quick health check after deploy |
| `LOAD_STAGES` | 10→20→50→100→100→0 | 5m | Sustained load, find plateaus |
| `STRESS_STAGES` | 50→100→200→400→0 | 9m | Find breaking point |

## Thresholds (SLOs)

```javascript
http_req_duration: ["p(95) < 200", "max < 1000"]
http_req_failed:   ["rate < 0.001"]     // < 0.1%
```

If a threshold is crossed and `abortOnFail: true`, the test stops immediately. See `config/options.js`.

## Running Tests

### Prerequisites

- Container runtime started (for the InfluxDB + Grafana stack): `colima start` — see ADR-016. Docker Desktop is deliberately not used and is not installed
- k6 CLI: `brew install k6`
- App running on `http://localhost:8080`

> **Verify writes by query, never by exit code.** The pipeline works as of
> 2026-08-23 (EOP-154, ADR-016) — it was broken from 2026-07-27 until then — but
> k6 **exits 0 even when every write fails**, logging `Couldn't write stats` once
> per flush interval and passing its thresholds regardless. So a green run proves
> nothing about InfluxDB. Confirm the data landed:
>
> ```bash
> curl -sG http://localhost:8086/query --data-urlencode 'db=k6' \
>   --data-urlencode 'q=SHOW MEASUREMENTS'
> ```
>
> If writes fail, suspect your shell before the stack: direnv exports
> `INFLUXDB_URL` when the shell loads `.envrc`, so editing `.env` in a running
> shell appears to do nothing until you `direnv reload` or open a new one.

### Quick smoke test (no monitoring stack)

```bash
k6 run test/k6/health-check.js
```

### Full stack (with Grafana dashboard)

```bash
# Terminal 1: Start monitoring stack
docker compose up -d

# Terminal 2: Start the app
./mvnw spring-boot:run

# Terminal 3: Run load test
test/k6/run.sh

# Open Grafana
open http://localhost:3000  # Dashboard → k6 Load Testing
```

### Run against a different target

```bash
BASE_URL=http://staging.example.com test/k6/run.sh
```

## Adding a New Test

1. Create `test/k6/<scenario-name>.js`
2. Import shared config from `config/options.js` (thresholds, stages)
3. Export `default` function with the test logic
4. Run via `test/k6/run.sh test/k6/<scenario-name>.js`

## InfluxDB Schema

k6 writes metrics to the `k6` database in InfluxDB:

| Measurement | Type | Key Fields |
|---|---|---|
| `http_req_duration` | Trend | `min`, `max`, `avg`, `p(50)`, `p(95)`, `p(99)`, `count` |
| `http_reqs` | Counter | `count`, `rate` (per second) |
| `http_req_failed` | Rate | `rate` (proportion 0-1), `count` |
| `vus` | Gauge | `value` |

## Golden Rules

1. **Always run against a production-like environment** — H2 in dev gives misleading latency numbers
2. **Never hardcode a URL in a test script** — read it from the `BASE_URL` env var. `run.sh` defaults it to `http://localhost:8080`; set it explicitly for anything that is not local dev
3. **Compare against baseline** — check the Grafana dashboard with a wider time range to see if performance regressed
4. **Check JSON results** — `docs/performance/history/` contains timestamped JSON output for offline analysis
5. **Document regressions** — append results to `docs/performance/TRENDS.md` after each meaningful test run. **Local runs only.** CI results have their own series and must never be appended here — see the next section for why the two populations cannot be mixed

## Two populations of measurement, and never one series (ADR-055 §5 as amended by EOP-169)

Performance figures in this repository come from two sources that are **individually comparable and mutually incomparable**. Keep them apart; a single trend line mixing them would show step changes caused by hardware rather than by code.

- **Local baselines** — everything above. Measured by hand on a developer Mac through Colima against a t3.small-bound topology, streamed to InfluxDB by `test/k6/run.sh`, viewed in Grafana on `:3000`, and curated into `docs/performance/TRENDS.md`. This is the only population the SLOs in this file (p95 < 200 ms, max < 1000 ms, error rate < 0.1%) are stated against
- **The CI canary series** — measured on a 2-vCPU shared GitHub Actions runner by the `Run k6 performance regression canary` step, against the relaxed `THRESHOLDS_CI` in `test/k6/config/options-ci.js` (p95 < 500 ms, max < 2000 ms, error rate unchanged). It is a smoke canary catching gross regressions — an N+1, a missing index, a 50× slowdown — not a load test, and its absolute numbers mean nothing next to a local figure

**Neither `run.sh` nor Grafana is involved in CI, and CI never writes to InfluxDB.** The local stack binds InfluxDB to loopback, and a hosted endpoint would need a repository secret the pipeline deliberately does not have. CI results reach a human three ways instead:

1. **The run summary** — every run, pull requests included, renders a metrics table into `$GITHUB_STEP_SUMMARY`. This is a separate `if: always()` step, so the numbers appear even when a threshold breach fails the canary — which is exactly when they are wanted
2. **The `k6-results` artifact** — `raw.json` plus `summary.json` for one run, for debugging a specific failure
3. **The trend** — on a push to `main` whose `image` job went green, the `perf-trend` job appends one flat JSON line to `ci-history.jsonl` on the orphan `perf-history` branch and republishes `tools/perf/trend-page.html` beside it as `index.html`. GitHub Pages serves that branch root

Rules for the CI series:

- **`tools/perf/trend-page.html` is the tracked source of the page.** Edit it there and never on `perf-history`, where CI overwrites `index.html` unconditionally on the next push
- **A row carries `date`, `sha`, `run_id`, `p50`, `p95`, `p99`, `max`, `rps`, `error_rate`, `iterations`, `checks`** — flat, one JSON object per line. `sha` and `run_id` are what tie a point to a commit and its logs, so a shape without them defeats the purpose
- **Read `error_rate` from `.metrics.http_req_failed.value`, never from `.passes`/`.fails`** — that Rate reports `passes: 0, fails: 221` on a fully healthy run, so the obvious reading records 100% errors
- **A missing or empty `summary.json` is a hard failure, never a skip.** k6 exits 0 even when every InfluxDB write fails (see ADR-016), and the first attempt at this job passed green while appending nothing at all because it read a gitignored workspace path instead of the artifact. Silence is the failure mode to design against
- Only a push to `main` appends. `workflow_dispatch` and the weekly `schedule` also report `refs/heads/main`, so the event type is checked as well as the ref
