import http from "k6/http";
import { check, sleep } from "k6";
import { THRESHOLDS, SMOKE_STAGES } from "./config/options.js";
import { THRESHOLDS_CI } from "./config/options-ci.js";

// Select thresholds based on environment
const USE_CI_THRESHOLDS = __ENV.K6_ENV === "ci";
const SELECTED_THRESHOLDS = USE_CI_THRESHOLDS ? THRESHOLDS_CI : THRESHOLDS;

// SMOKE_STAGES only, and that is a constraint rather than a default (EOP-156).
// LOAD_STAGES and STRESS_STAGES stay local-only: this scenario runs inside the
// `image` job on a 2-vCPU shared runner alongside Postgres, Caddy and the
// application, so a 100- or 400-VU profile would measure the runner rather than
// the endpoint. It is a canary, not a load test.
export const options = {
  stages: SMOKE_STAGES,
  thresholds: SELECTED_THRESHOLDS,
  summaryTrendStats: ["avg", "min", "med", "max", "p(50)", "p(95)", "p(99)"],
};

// See health-check.js for the full rationale behind this default: the
// application publishes no host port of its own (ADR-017), so the proxy on 443
// is the only path in, and Caddy's `tls internal` certificate is self-signed --
// run k6 with --insecure-skip-tls-verify or trust the local CA. A native
// `./mvnw spring-boot:run` does bind 8080 on the host, so pass BASE_URL
// explicitly for that rather than reintroducing a branch here.
const BASE_URL = __ENV.BASE_URL || "https://localhost";

// `size=1` keeps the payload to a single card while still traversing the whole
// stack -- Caddy, Tomcat, the controller, the catalogue query, JPA mapping,
// Liquibase's schema and Postgres -- which is the point of this scenario. It is
// the request the `image` job's smoke test already proves reachable, so the
// canary adds load measurement to a path known to work rather than a new
// dependency. Page<T> serialisation is exercised by reading the whole envelope.
export default function () {
  const res = http.get(`${BASE_URL}/api/v1/cards?size=1`);

  // Correctness checks only, deliberately with no latency assertion. The
  // thresholds above own latency, and a per-request millisecond check here
  // would report noise from a shared runner as a failed check without ever
  // failing the build. These instead catch the case a threshold cannot see: an
  // endpoint answering 200 with an error document or an empty page, which would
  // otherwise register as a suspiciously fast success.
  //
  // The seeded deck size is asserted by the smoke test immediately before this
  // step and is not repeated here, so a legitimate change to the catalogue does
  // not have to be applied in two places to keep the pipeline green.
  //
  // All five fields CardPage declares as required are now covered (EOP-203):
  // content, page, size, totalElements and totalPages. The last of those is
  // computed rather than stored, so asserting it exercises Page.getTotalPages()
  // -- a path nothing else in the canary reaches.
  //
  // page, size and totalPages are asserted as invariants that hold at size=1 for
  // any deck size, not against literals. `totalPages === totalElements` is true
  // for every catalogue when the page holds one element, so it still fails if
  // getTotalPages() breaks or if the pagination parameters stop round-tripping,
  // while leaving the smoke test the single place that knows the deck is 68 cards.
  check(res, {
    "status is 200": (r) => r.status === 200,
    "body is a Page": (r) => Array.isArray(r.json("content")),
    "page holds the requested single card": (r) => r.json("content").length === 1,
    "catalogue is not empty": (r) => r.json("totalElements") > 0,
    "page is the first page": (r) => r.json("page") === 0,
    "size echoes the request": (r) => r.json("size") === 1,
    "totalPages matches the element count at size=1": (r) =>
      r.json("totalPages") === r.json("totalElements"),
  });

  sleep(1);
}
