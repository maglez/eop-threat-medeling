#!/usr/bin/env bash
# The SonarQube issue ratchet for the front end. Compares the committed
# front-end scan report against the committed front-end ceiling and fails if the
# count of Reliability, Maintainability or Security issues has risen. See
# ADR-063, which adds this second project, and ADR-060, which established the
# mechanism for the Java one.
#
# This is the command CI runs, and it is deliberately the same command a
# developer runs locally. The whole gate lives here rather than in the workflow
# YAML so that reproducing a CI failure is one line and needs no act, no
# secrets and no container:
#
#     tools/sonar/ratchet-ui.sh
#
# It talks to nothing. No SonarQube server, no network, no container runtime -
# just two JSON files and the working tree. Same requirement as the Java gate:
# export the report locally, commit it, compare in CI without a live server.
#
# Exit codes, the contract shared with tools/sonar/ratchet.sh and
# tools/supply-chain/:
#   0  clean
#   1  a gating finding - a count rose, or the committed report is stale
#   2  could not run - a file is missing, python3 is absent, unparseable JSON
#
# Usage:
#   tools/sonar/ratchet-ui.sh              # check only (what CI does)
#   tools/sonar/ratchet-ui.sh --tighten    # check, and lower the ceiling if cleaner
#
# --tighten is for local runs only, and tools/sonar/scan-ui.sh passes it at the
# end of a scan. CI never does, because CI must not write to the repository.
#
# WHY THIS WRAPPER EXISTS AT ALL
#
# It is four lines of substance: compute the front-end freshness token, and hand
# it to the one ratchet implementation with --flavour ui. Everything that decides
# whether the gate passes lives in tools/sonar/ratchet.py, shared with the Java
# project, because the delicate logic there is the refusal to compare a stale
# report and the refusal to infer a missing count as zero - and two copies of a
# refusal is one copy that can rot unnoticed while the other is maintained.
#
# What is *not* shared is the freshness token, and that is the point of the
# split. sonar_ui_source_hash covers ui/package.json, ui/tsconfig.json,
# ui/vite.config.ts and every .ts/.tsx under ui/src; sonar_source_hash covers
# pom.xml and every .java file. The two sets are disjoint, so a Java-only change
# leaves this report fresh and a front-end-only change leaves the Java report
# fresh. Neither project forces a rescan of the other.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=tools/sonar/source-hash.sh
source tools/sonar/source-hash.sh

command -v python3 >/dev/null 2>&1 || {
    echo "sonar-ratchet-ui: cannot run - python3 is not on PATH" >&2
    exit 2
}

# Computed here, in shell, rather than inside ratchet.py - because the
# definition has to be shared with scan-ui.sh and a sourced shell library is the
# only way both scripts can reach one copy of it. Re-implementing the same
# find/sort/digest pipeline in Python would give us two definitions of the
# freshness token that agree until the day they do not, and a drifted hash
# presents as "your report is stale" on a tree that was scanned five seconds
# ago. Compute it once, pass it in.
actual_hash=$(sonar_ui_source_hash)
actual_file_count=$(sonar_ui_source_file_count)

# --flavour ui selects the front-end report and baseline paths, the
# sonar-ratchet-ui log prefix, and the remedy text naming scan-ui.sh. It is one
# flag rather than four overrides deliberately: a wrapper that passed three of
# four would produce a gate that reported the right numbers under the wrong
# name. See the FLAVOURS table in ratchet.py.
exec python3 tools/sonar/ratchet.py \
    --flavour ui \
    --actual-hash "$actual_hash" \
    --actual-file-count "$actual_file_count" \
    "$@"
