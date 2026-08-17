#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$REPO_ROOT/.server.pid"

if [[ -f "$PID_FILE" ]]; then
  PID=$(cat "$PID_FILE")
  if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping server (PID $PID)..."
    kill "$PID"
    rm -f "$PID_FILE"
    echo "Stopped. H2 in-memory DB is gone."
  else
    echo "PID $PID not running — cleaning up stale pid file."
    rm -f "$PID_FILE"
  fi
else
  # Fallback: kill by port
  lsof -ti tcp:8080 | xargs kill -9 2>/dev/null && echo "Killed process on :8080." || echo "Nothing running on :8080."
fi
