# ADR-067: Log aggregation pipeline via Loki and Promtail

**Status:** Accepted

**Date:** 2026-09-05

**Deciders:** @tech-lead, @devops-engineer

## Context

EOP-209 contracts a log aggregation pipeline for the local developer stack. The existing
monitoring stack (k6, InfluxDB, Grafana) serves performance testing — see
`.opencode/rules/performance-testing.md` — and the log pipeline is a separate, opt-in
concern. The ticket specifies Loki for storage/query, Promtail for collection, Grafana for
visualisation, a 30-day retention, and a `correlationId` index label.

Three empirical findings constrain the design.

1. **logback 1.5.x `JsonEncoder` field names differ from what the source comments claim.**
   The actual emitted JSON fields are: `sequenceNumber`, `timestamp` (**epoch millis as a
   number, not ISO-8601**), `nanoseconds`, `level`, `threadName`, `loggerName`, `context`,
   `markers`, `mdc` (a nested object), `message`, `arguments`, `throwable`. The field is
   `loggerName`, not `logger` — the comment at `logback-spring.xml` line 49 is wrong on both
   counts. The correlation ID therefore extracts as `mdc.correlationId`, since MDC entries are
   nested rather than emitted at the top level.

2. **`message` carries unformatted SLF4J placeholders.** A real emitted line:
   ```
   "message":"Rejected caller input: {}","arguments": ["page must not be negative, was -1"]
   ```
   The `{}` placeholder remains in `message` and the substituted values sit in a separate
   `arguments` array. A Loki line filter must not expect interpolated text.

3. **`correlationId` as an index label is an unbounded-cardinality anti-pattern.** One
   stream per request would result from indexing a per-request UUID, which is documented
   Loki anti-pattern. Loki 3.3.2 on tsdb schema v13 supports structured metadata, which is
   the purpose-built home for request/trace IDs. Verified empirically: the query
   `{container="eop-app", level="WARN"} | correlationId="..."` returns the line **without**
   a `| json` parser in the pipeline, proving it is genuinely structured metadata.

## Decision

Deploy a separate `compose.observability.yml` file containing Loki and Promtail, reusing the
existing Grafana from `docker-compose.yml`.

**Loki** (`grafana/loki:3.3.2` digest-pinned):
- Single-binary filesystem deployment, no clustering — local developer stack only.
- HTTP on `127.0.0.1:3100` (loopback only, matching the InfluxDB house pattern).
- 30-day retention (`limits_config.retention_period: 720h`).
- tsdb schema v13, filesystem object store.
- `auth_enabled: false` — no authentication, loopback binding is the boundary.

**Promtail** (`grafana/promtail:3.3.2` digest-pinned):
- Docker service discovery against `/var/run/docker.sock` with a 5-second refresh and name
  filter `["eop*"]`.
- Two mutually exclusive branches, selected by a line filter:
  - `'{container="eop-app"} |= "timestamp"'` → JSON branch: extracts `level`, `loggerName`
    (not `logger`), and `mdc.correlationId` via JMESPath, labels `level` and `logger`, and
    stores `correlationId` as **structured metadata**.
  - `'{container="eop-app"} != "timestamp"'` → plain-text branch: regex parser for Spring
    Boot's DEFAULT console pattern, same labels.
- The discriminator exists because `logback-spring.xml` (EOP-117) selects the JSON encoder
  under the `prod` profile and a plain pattern otherwise, and the container runs
  `SPRING_PROFILES_ACTIVE=prod`, so the JSON branch is the live path.

**Grafana datasource** (`tools/monitoring/grafana/datasources/datasource.yml`):
- Extends the existing InfluxDB provisioning rather than replacing it.
- Adds Loki as a second datasource (`uid: Loki`, `url: http://loki:3100`, `isDefault: false`).

**Dashboard** (`tools/monitoring/grafana/dashboards/eop-infrastructure.json`):
- Four panels: Error Rate Over Time, Error Type Breakdown by Logger, Log Volume Over Time,
  Log Level Distribution — all LogQL over `{container=~"eop.*"}`.

**Container pins** extend [ADR-064](ADR-064-pinned-container-audit-coverage.md)'s roster from
three to five pinned images. Both Loki and Promtail carry no provenance attestation — an
accepted residual consistent with the existing position in `SETUP.md`.

### Scope deliberately not taken

- **CI integration.** Nothing in `.github/workflows/` is touched. The pipeline is a local
  developer tool, not a CI concern.
- **HTTP latency and status panels.** These cannot be built from current logs:
  `CorrelationIdFilter` populates MDC but logs nothing, no controller logs a status code or
  elapsed time, and `logback-spring.xml` declares no such field. Verified: `POST
  /api/v1/sessions` with `{"displayName":""}` returns 400 with an `x-correlation-id` response
  header — proving the filter ran — yet `docker logs eop-app` contains **zero** lines for it.
  This is a standing violation of `.opencode/rules/observability.md`. It is filed as
  **EOP-212** under Epic **EOP-208** and linked `Relates to` EOP-209. The dashboard is honest
  about what the logs can currently support.
- **A second compose file, not an addition to `docker-compose.yml`.** The existing stack is
  the k6/InfluxDB/Grafana performance harness; the log pipeline is opt-in and independently
  startable.
- **Security control.** `auth_enabled: false` and Loki bound to loopback. Do not describe
  loopback binding as a security boundary beyond what it is.

### Coupling to implicit Compose naming

Both Loki and Promtail join `networks: eop-monitoring_default` declared `external: true`.
`docker-compose.yml` line 5 is `name: eop-monitoring` and declares no explicit `networks:`
block, so Compose creates its implicit default as `eop-monitoring_default`. This is how the
pre-existing Grafana reaches Loki by service name. **Consequence:** `docker compose -f
docker-compose.yml up -d` must already be running, or the external network does not exist.
This is a coupling to an implicit Compose naming convention — record it as a risk.

## Consequences

- Developers can query structured logs via Grafana with 30-day retention.
- `correlationId` is queryable as structured metadata without inflating the index cardinality.
- The two new container images are covered by the ADR-064 audit roster.
- The dashboard is limited to what the application currently logs — HTTP latency and status
  panels require EOP-212.
- The external network coupling requires the base stack to be running before the observability
  stack.
- No authentication on Loki; loopback binding is the only boundary.
- Nothing is wired into CI — this is a local developer tool only.

## Related

- [ADR-064](ADR-064-pinned-container-audit-coverage.md) — the container audit this extends
- [ADR-016](ADR-016-local-container-runtime.md) — Colima as the local container runtime
- EOP-117 — the story that added `logback-spring.xml`, the JSON encoder and the
  `CorrelationIdFilter` that populates MDC. There is no ADR for it
- `compose.observability.yml` — the new compose file
- `tools/monitoring/loki/loki-config.yml` — Loki configuration
- `tools/monitoring/promtail/promtail-config.yml` — Promtail configuration
- `tools/monitoring/grafana/datasources/datasource.yml` — Grafana provisioning
- `tools/monitoring/grafana/dashboards/eop-infrastructure.json` — the dashboard
- EOP-212 — HTTP observability gap (linked to EOP-209)