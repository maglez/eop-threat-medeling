#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/.env"
PROVIDER="${1:-}"

case "$PROVIDER" in
  zen)
    echo "Switching to OpenCode Zen..."
    # Comment out MODEL_ lines in the Bedrock block
    sed -i '' -e '/^# === Bedrock ===/,/^# AWS/{
      /^MODEL_/s/^/#/
    }' "$ENV_FILE"
    # Uncomment #MODEL_ lines in the Zen block
    sed -i '' -e '/^# === Zen ===/,/^# === Bedrock ===/{
      /^#MODEL_/s/^#//
    }' "$ENV_FILE"
    echo "Done. Restart opencode for the change to take effect."
    ;;
  bedrock)
    echo "Switching to AWS Bedrock..."
    # Comment out MODEL_ lines in the Zen block
    sed -i '' -e '/^# === Zen ===/,/^# === Bedrock ===/{
      /^MODEL_/s/^/#/
    }' "$ENV_FILE"
    # Uncomment #MODEL_ lines in the Bedrock block
    sed -i '' -e '/^# === Bedrock ===/,/^# AWS/{
      /^#MODEL_/s/^#//
    }' "$ENV_FILE"
    echo "Done. Restart opencode for the change to take effect."
    ;;
  *)
    echo "Usage: $0 [zen|bedrock]"
    echo "  zen     — switch to OpenCode Zen provider"
    echo "  bedrock — switch to AWS Bedrock provider"
    exit 1
    ;;
esac
