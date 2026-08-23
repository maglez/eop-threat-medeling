// CI-specific thresholds for k6 performance checks.
// These are relaxed compared to local development thresholds to account
// for 2-vCPU runner jitter while still catching gross regressions.
//
// Rationale:
// - 500ms p(95) is 2.5× the SLO (200ms) and ~50× the observed baseline
//   (9.856ms), immune to shared-2-vCPU-runner jitter while still catching
//   gross regression (N+1, missing index, 50× slowdown).
// - http_req_failed is NOT relaxed, because a non-2xx is never runner noise.
export const THRESHOLDS_CI = {
  http_req_duration: [
    { threshold: "p(95) < 500", abortOnFail: true, delayAbortEval: "5s" },
    { threshold: "max < 2000", abortOnFail: false },
  ],
  http_req_failed: [
    { threshold: "rate < 0.001", abortOnFail: true, delayAbortEval: "5s" },
  ],
};