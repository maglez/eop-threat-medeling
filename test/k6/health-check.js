import http from "k6/http";
import { check, sleep } from "k6";
import { THRESHOLDS, SMOKE_STAGES } from "./config/options.js";

export const options = {
  stages: SMOKE_STAGES,
  thresholds: THRESHOLDS,
  summaryTrendStats: ["avg", "min", "med", "max", "p(50)", "p(95)", "p(99)"],
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export default function () {
  const res = http.get(`${BASE_URL}/health`);

  check(res, {
    "status is 200": (r) => r.status === 200,
    "response body is OK": (r) => r.body === "OK",
    "response time < 100ms": (r) => r.timings.duration < 100,
  });

  sleep(1);
}
