# Performance Testing Conventions — k6 + InfluxDB + Grafana

## Authority

Load testing uses **k6**, with results streamed to **InfluxDB** and visualised via **Grafana** (provisioned dashboard). Baseline tracking follows the performance engineer agent conventions in `agents/team-member-performance-engineer.md`.

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

- Docker Desktop (for InfluxDB + Grafana stack): `open /Applications/Docker.app`
- k6 CLI: `brew install k6`
- App running on `http://localhost:8080`

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

k6 writes metrics to the `k6` database in InfluxDB 1.8:

| Measurement | Type | Key Fields |
|---|---|---|
| `http_req_duration` | Trend | `min`, `max`, `avg`, `p(50)`, `p(95)`, `p(99)`, `count` |
| `http_reqs` | Counter | `count`, `rate` (per second) |
| `http_req_failed` | Rate | `rate` (proportion 0-1), `count` |
| `vus` | Gauge | `value` |

## Golden Rules

1. **Always run against a production-like environment** — H2 in dev gives misleading latency numbers
2. **Set `BASE_URL` explicitly** — never hardcode URLs in test scripts
3. **Compare against baseline** — check the Grafana dashboard with a wider time range to see if performance regressed
4. **Check JSON results** — `docs/performance/history/` contains timestamped JSON output for offline analysis
5. **Document regressions** — append results to `docs/performance/TRENDS.md` after each meaningful test run
