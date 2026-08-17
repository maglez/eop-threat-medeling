#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Kill anything already on 8080
lsof -ti tcp:8080 | xargs kill -9 2>/dev/null || true

echo "Starting Spring Boot on :8080 (H2 in-memory — fresh DB on every start)..."
cd "$REPO_ROOT"
./mvnw spring-boot:run &
SERVER_PID=$!
echo "$SERVER_PID" > "$REPO_ROOT/.server.pid"

# Wait for /health
echo "Waiting for /health..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/health > /dev/null 2>&1; then
    echo "Server up (PID $SERVER_PID)"
    exit 0
  fi
  sleep 1
done

echo "ERROR: server did not start within 30s" >&2
exit 1
