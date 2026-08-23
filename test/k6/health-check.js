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

  check(res, {
    "status is 200": (r) => r.status === 200,
    "response body is OK": (r) => r.body === "OK",
    "response time < 100ms": (r) => r.timings.duration < 100,
  });

  sleep(1);
}
