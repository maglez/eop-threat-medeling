#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%S)
RESULT_DIR="$PROJECT_DIR/docs/performance/history"

mkdir -p "$RESULT_DIR"

INFLUXDB_URL="${INFLUXDB_URL:-http://localhost:8086/k6}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
TEST_SCRIPT="${1:-$SCRIPT_DIR/health-check.js}"
TEST_NAME="${2:-$(basename "$TEST_SCRIPT" .js)}"
OUTPUT_JSON="$RESULT_DIR/${TEST_NAME}-${TIMESTAMP}.json"

echo "=== k6 Load Test ==="
echo "  Script:   $TEST_SCRIPT"
echo "  Target:   $BASE_URL"
echo "  InfluxDB: $INFLUXDB_URL"
echo "  JSON out: $OUTPUT_JSON"

k6 run "$TEST_SCRIPT" \
  --out json="$OUTPUT_JSON" \
  --out influxdb="$INFLUXDB_URL" \
  -e BASE_URL="$BASE_URL" \
  --summary-export="$RESULT_DIR/${TEST_NAME}-${TIMESTAMP}-summary.json"

echo ""
echo "  Results saved to:"
echo "    JSON:   $OUTPUT_JSON"
echo "    Influx: $INFLUXDB_URL"
echo "    Grafana: http://localhost:3000 (Dashboard: k6 Load Testing)"
