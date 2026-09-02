#!/usr/bin/env bash
# The SonarQube issue ratchet. Compares the committed scan report against the
# committed ceiling and fails if the count of Reliability, Maintainability or
# Security issues has risen. See ADR-060.
#
# This is the command CI runs, and it is deliberately the same command a
# developer runs locally. The whole gate lives here rather than in the workflow
# YAML so that reproducing a CI failure is one line and needs no act, no
# secrets and no container:
#
#     tools/sonar/ratchet.sh
#
# It talks to nothing. No SonarQube server, no network, no container runtime -
# just two JSON files and the working tree. That was the explicit requirement:
# export the report locally, commit it, compare in CI without a live server.
#
# Exit codes, the contract shared with tools/supply-chain/:
#   0  clean
#   1  a gating finding - a count rose, or the committed report is stale
#   2  could not run - a file is missing, python3 is absent, unparseable JSON
#
# Usage:
#   tools/sonar/ratchet.sh              # check only (what CI does)
#   tools/sonar/ratchet.sh --tighten    # check, and lower the ceiling if cleaner
#
# --tighten is for local runs only, and tools/sonar/scan.sh passes it at the end
# of a scan. CI never does, because CI must not write to the repository.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=tools/sonar/source-hash.sh
source tools/sonar/source-hash.sh

command -v python3 >/dev/null 2>&1 || {
    echo "sonar-ratchet: cannot run - python3 is not on PATH" >&2
    exit 2
}

# Computed here, in shell, rather than inside ratchet.py - because the
# definition has to be shared with scan.sh and a sourced shell library is the
# only way both scripts can reach one copy of it. Re-implementing the same
# find/sort/digest pipeline in Python would give us two definitions of the
# freshness token that agree until the day they do not, and a drifted hash
# presents as "your report is stale" on a tree that was scanned five seconds
# ago. Compute it once, pass it in.
actual_hash=$(sonar_source_hash)
actual_file_count=$(sonar_source_file_count)

exec python3 tools/sonar/ratchet.py \
    --actual-hash "$actual_hash" \
    --actual-file-count "$actual_file_count" \
    "$@"
