# Performance Trends

This document tracks load test results over time. Automated entries appended by `tools/perf-report/` when available.

## Latest Results

| Date | Test | p50 (ms) | p95 (ms) | p99 (ms) | Req/s | Error % | SLOs |
|---|---|---|---|---|---|---|---|
| — | _No runs yet_ | — | — | — | — | — | — |

## Baseline

| Metric | Target | Current |
|---|---|---|
| p95 Latency | < 200ms | — |
| Max Latency | < 1000ms | — |
| Error Rate | < 0.1% | — |

---

> See `tools/monitoring/` for the Docker monitoring stack.
> See `test/k6/` for load test scripts.
> Grafana dashboard: http://localhost:3000 (Dashboard: k6 Load Testing)
