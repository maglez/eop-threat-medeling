export const THRESHOLDS = {
  http_req_duration: [
    { threshold: "p(95) < 200", abortOnFail: true, delayAbortEval: "5s" },
    { threshold: "max < 1000", abortOnFail: false },
  ],
  http_req_failed: [
    { threshold: "rate < 0.001", abortOnFail: true, delayAbortEval: "5s" },
  ],
};

export const SMOKE_STAGES = [
  { duration: "10s", target: 5 },
  { duration: "20s", target: 10 },
  { duration: "10s", target: 0 },
];

export const LOAD_STAGES = [
  { duration: "30s", target: 10 },
  { duration: "1m", target: 20 },
  { duration: "1m", target: 50 },
  { duration: "30s", target: 100 },
  { duration: "1m", target: 100 },
  { duration: "30s", target: 0 },
];

export const STRESS_STAGES = [
  { duration: "2m", target: 50 },
  { duration: "2m", target: 100 },
  { duration: "2m", target: 200 },
  { duration: "2m", target: 400 },
  { duration: "1m", target: 0 },
];
