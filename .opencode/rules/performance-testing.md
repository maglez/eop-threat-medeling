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
5. **Document regressions** — append results to `docs/performance/TRENDS.md` after each meaningful test run
