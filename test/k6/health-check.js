import http from "k6/http";
import { check, sleep } from "k6";
import { THRESHOLDS, SMOKE_STAGES } from "./config/options.js";
import { THRESHOLDS_CI } from "./config/options-ci.js";

// Select thresholds based on environment
const USE_CI_THRESHOLDS = __ENV.K6_ENV === "ci";
const SELECTED_THRESHOLDS = USE_CI_THRESHOLDS ? THRESHOLDS_CI : THRESHOLDS;

export const options = {
  stages: SMOKE_STAGES,
  thresholds: SELECTED_THRESHOLDS,
  summaryTrendStats: ["avg", "min", "med", "max", "p(50)", "p(95)", "p(99)"],
};

// Port 443 through the reverse proxy (EOP-21; was port 80). The application
// publishes no host port at all (ADR-017), so this is the path users take.
// NOTE: the Caddy local CA certificate is self-signed (tls internal). Run k6
// with --insecure-skip-tls-verify, or trust the Caddy CA in the system store.
// The default is deliberately unconditional. In every containerised environment,
// CI included, compose.app.yml gives the app service no `ports:` at all, so only
// Caddy publishes (443 -> 8080) and http://localhost:8080 is refused. A native
// `./mvnw spring-boot:run` does bind 8080 on the host, so if that is the target
// pass BASE_URL explicitly rather than reintroducing a branch here.
const BASE_URL = __ENV.BASE_URL || "https://localhost";

export default function () {
  const res = http.get(`${BASE_URL}/health`);

  // Correctness checks only, deliberately with no latency assertion -- the same
  // position card-catalogue.js takes. A `response time < 100ms` check used to sit
  // here; EOP-157 deleted it rather than renaming it, for two reasons.
  //
  // It gated nothing. A k6 check never affects the exit code, and no threshold
  // covers `checks` or `checks_failed`, so a 100-500ms response failed that check
  // silently while passing every threshold. Latency is owned by the thresholds
  // above -- p(95) < 500 in CI (options-ci.js), p(95) < 200 locally -- and those
  // are the only latency signal that can fail a build.
  //
  // Relabelling it "advisory" was rejected because this scenario's summary export
  // is the one the perf-trend job reads. That job records `checks` as a
  // passes/(passes+fails) count in ci-history.jsonl on the perf-history branch,
  // and tools/perf/trend-page.html renders the string verbatim. Both consumers
  // see counts; neither sees names. So an advisory label would have left a
  // permanent trend column oscillating with shared-runner noise rather than with
  // code, which a rename cannot fix. summaryTrendStats above already publishes
  // avg/min/med/max/p(50)/p(95)/p(99) -- a better latency picture than a binary
  // counter ever gave.
  //
  // Consequence when comparing against a historical figure: a local smoke run now
  // reports 442 of 442 checks (221 iterations x 2), not the 663 of 663 recorded in
  // TRENDS.md and ADR-055 for runs predating this change. That drop is this
  // deletion, not a regression.
  check(res, {
    "status is 200": (r) => r.status === 200,
    "response body is OK": (r) => r.body === "OK",
  });

  sleep(1);
}
