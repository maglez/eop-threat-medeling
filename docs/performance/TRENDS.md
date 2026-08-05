# Performance Trends

This document tracks load test results over time. Every run also writes a timestamped JSON pair
into `docs/performance/history/`, which is gitignored — those files are the raw evidence, this
document is the curated record.

## Latest Results

| Date | Test | Target | p50 (ms) | p95 (ms) | p99 (ms) | Req/s | Error % | SLOs |
|---|---|---|---|---|---|---|---|---|
| 2026-08-05 | health-check | Through reverse proxy, port 80 | 3.88 | 9.85 | 16.02 | 5.5 | 0.00 | Pass |
| 2026-08-05 | health-check | Direct to application, port 8080 | 3.25 | 5.77 | 11.93 | 5.5 | 0.00 | Pass (superseded) |

Both rows are 221 iterations over 40.2s ramping 1 → 10 virtual users, 663 of 663 checks passing.

## Baseline

| Metric | Target | Current | Headroom |
|---|---|---|---|
| p95 Latency | < 200ms | 9.85ms | ~20× |
| Max Latency | < 1000ms | 37.67ms | ~27× |
| Error Rate | < 0.1% | 0.00% | — |

## Baseline reset — 2026-08-05

**The baseline was deliberately reset, not improved or regressed.** The earlier figure (p95 5.77ms)
was measured against the application container's own published port. Since ADR-017 the application
publishes no host port at all: every real request arrives at the reverse proxy and is forwarded on
the same origin. So that number described a path no user takes, and comparing against it would
compare two different systems.

The proxy hop costs roughly 4ms at the ninety-fifth percentile. That is the honest cost of the
topology, and it still leaves around twenty times the margin against the threshold.

Treat the "Through reverse proxy" row as the baseline for every future comparison. The direct row is
retained only to show what changed and why, and should not be read as a target to return to.

### On the figures before that

There were none. This document carried `— | _No runs yet_` placeholders from its creation until
2026-08-05, because there was no container runtime on the development machine and the load test had
never run against a container anywhere. Any latency figure quoted in this repository before
2026-08-05 described a development-mode JVM, not the shipping artifact.

---

> See `tools/monitoring/` for the Docker monitoring stack, and `test/k6/` for the load test scripts.
>
> **Known broken:** k6 results never reach InfluxDB, so the Grafana dashboard at
> http://localhost:3000 is empty. k6 logs one write error per flush interval but exits `0`, so
> thresholds still gate correctly and the JSON files in `docs/performance/history/` remain the real
> evidence. See the Known issues section of `CHANGELOG.md` for the three causes.
