#!/usr/bin/env bash
#
# Scan the front end with SonarQube and refresh tools/sonar/sonar-ui-report.json.
#
# This is the front-end counterpart of tools/sonar/scan.sh. The two are separate
# SonarQube projects on purpose (ADR-063): eop-threat-modeling for the Java tree
# and eop-threat-modeling-ui for ui/. Each has its own ceiling, its own freshness
# token and its own CI job, so a regression in one cannot be paid for out of the
# other's headroom.
#
# WHAT IT DOES
#
#   1. Preflights the local SonarQube server and mints a token if you have none.
#   2. Runs `npm run coverage` in ui/, which runs the Vitest suite and writes
#      ui/coverage/lcov.info. Skip with --skip-build if you already have one.
#   3. Runs the SonarScanner CLI in a digest-pinned container against ui/.
#   4. Waits for the compute engine to finish, so the numbers we harvest are the
#      ones the scanner just uploaded rather than the previous analysis.
#   5. Harvests the counts and the production issue inventory and writes the
#      report atomically.
#   6. Hands off to tools/sonar/ratchet-ui.sh --tighten.
#
# WHY THE SCANNER RUNS IN A CONTAINER AND NOT FROM PATH
#
# The Java path resolves its scanner by fully qualified Maven coordinates -
# exact, reproducible, and nothing to install. There is no equivalent for the
# standalone CLI, so the choice was between `brew install sonar-scanner` and a
# container. A brew-installed CLI floats with `brew upgrade`, and this script
# records the scanner it used in the report, so a floating version would drift
# the committed artefact silently. Pinning the image by digest gives the Java
# path's property back. It also adds no prerequisite: SonarQube itself only runs
# here through compose.sonar.yml, so docker was already required.
#
# The image is amd64-only and therefore runs under emulation on Apple silicon.
# That is slow but correct, and --platform is passed explicitly so the platform
# is a decision in this file rather than a warning in the output.
#
# EXIT CODES
#
#   0  the ratchet passed - counts are at or below the ceiling
#   1  the ratchet failed - a count rose, or the report is stale
#   2  the scan could not be completed (no server, no docker, bad credentials)
#
# The 1-versus-2 split is the same as scan.sh's and matters for the same reason:
# 1 is a finding about the code, 2 is a broken toolchain. Only 1 should ever be
# read as "the front end got worse".
#
# USAGE
#
#   tools/sonar/scan-ui.sh                 # coverage run, scan, ratchet
#   tools/sonar/scan-ui.sh --skip-build    # reuse the existing lcov.info
#
# ENVIRONMENT
#
#   SONAR_URL         default http://localhost:9000
#   SONAR_TOKEN       if unset, a token named eop-local-scan is minted
#   SONAR_ADMIN_USER  default admin, used only to mint that token
#   SONAR_ADMIN_PASS  default admin, likewise
#
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# shellcheck source=tools/sonar/source-hash.sh
source tools/sonar/source-hash.sh

SONAR_URL="${SONAR_URL:-http://localhost:9000}"
SONAR_ADMIN_USER="${SONAR_ADMIN_USER:-admin}"
SONAR_ADMIN_PASS="${SONAR_ADMIN_PASS:-admin}"

PROJECT_KEY="eop-threat-modeling-ui"
PROJECT_NAME="Elevation of Privilege Threat Modeling - UI"

# Pinned by digest rather than by tag, for the reason compose.sonar.yml pins
# SonarQube the same way: a tag is a moving target and this script writes the
# scanner it used into a committed file. Bumping it is a deliberate edit here,
# and the report changes in the same commit.
SCANNER_IMAGE="sonarsource/sonar-scanner-cli@sha256:23ca0f137965d9dff2198074043fd48d386280bc5d0ccac8c8349cea4cf096a9"

REPORT="tools/sonar/sonar-ui-report.json"

# There is deliberately no BASELINE variable here. This script never touches the
# baseline itself - it writes the report and then delegates every decision about
# the ceiling to ratchet-ui.sh, which is the same entry point CI uses. Two paths
# into a ceiling is how the two come to disagree.

skip_build=0
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)
            skip_build=1
            shift
            ;;
        -h | --help)
            sed -n '1,70p' "$0"
            exit 0
            ;;
        *)
            echo "sonar-scan-ui: unknown argument '$1'" >&2
            exit 2
            ;;
    esac
done

die() {
    echo "sonar-scan-ui: $*" >&2
    exit 2
}

for tool in curl python3 docker; do
    command -v "$tool" >/dev/null 2>&1 || die "'$tool' is not on PATH"
done

# --- server preflight -------------------------------------------------------
#
# Fail here with a recipe rather than letting the scanner container fail with a
# connection error 90 seconds into an emulated JVM startup.

status=$(curl -fsS --max-time 10 "$SONAR_URL/api/system/status" 2>/dev/null || true)
case "$status" in
    *'"status":"UP"'*) ;;
    '')
        die "no SonarQube at $SONAR_URL. Start it with:
    colima start
    docker compose -f compose.sonar.yml up -d
  then wait for the container to report healthy."
        ;;
    *)
        die "SonarQube at $SONAR_URL is not ready yet: $status"
        ;;
esac

server_version=$(printf '%s' "$status" | python3 -c 'import sys,json; print(json.load(sys.stdin)["version"])') ||
    die "could not read the version out of $SONAR_URL/api/system/status"

echo "sonar-scan-ui: server $server_version at $SONAR_URL"

# --- token ------------------------------------------------------------------
#
# Revoke-then-generate a fixed name. Token names are unique, so generating
# eop-local-scan twice fails on the second run; timestamped names would work but
# would accumulate live credentials on the server, one per scan, none revoked.
#
# The name is shared with tools/sonar/scan.sh deliberately, for that same reason
# - one credential rather than one per script. Running the two scans one after
# the other is therefore safe, because each mints the token it is about to use.
# Running them CONCURRENTLY is not: whichever revokes second invalidates the
# other's token mid-scan, and the running scan then fails on its next API call
# with a rejected token rather than anything that names this as the cause. Both
# scans need the same single-container SonarQube and neither is quick, so if you
# want them in parallel, export one SONAR_TOKEN for both - that skips this block
# entirely in both scripts and no revoke ever happens.

if [ -z "${SONAR_TOKEN:-}" ]; then
    curl -fsS -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASS" -X POST \
        "$SONAR_URL/api/user_tokens/revoke" -d 'name=eop-local-scan' >/dev/null 2>&1 || true

    SONAR_TOKEN=$(curl -fsS -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASS" -X POST \
        "$SONAR_URL/api/user_tokens/generate" -d 'name=eop-local-scan' -d 'type=USER_TOKEN' |
        python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])') ||
        die "could not mint a token as $SONAR_ADMIN_USER. Set SONAR_TOKEN, or fix the credentials."
fi

# Every read of the SonarQube API goes through here. The `|| die` sits at this
# seam so that curl's own exit codes - 22 for an HTTP error, 28 for a timeout, 7
# for a refused connection - all become this script's contracted 2 rather than
# leaking out as themselves. It works through command substitution because the
# assignment inherits the failure. Two call sites pipe this into python and so
# need their own `|| die`; they have one.
api() {
    curl -fsS --max-time 60 -u "$SONAR_TOKEN:" "$SONAR_URL$1" ||
        die "$SONAR_URL$1 did not answer - is the SonarQube container still healthy?"
}

api '/api/authentication/validate' | grep -q '"valid":true' ||
    die "the token was rejected by $SONAR_URL"

# --- coverage ---------------------------------------------------------------
#
# `npm run coverage` runs the Vitest suite and emits ui/coverage/lcov.info in one
# pass. Without it SonarQube still analyses the TypeScript and still reports
# issue counts - it just reports 0% coverage, which is worse than no number at
# all because it looks like a measurement.

if [ "$skip_build" -eq 0 ]; then
    echo "sonar-scan-ui: running the front-end coverage build"
    (cd ui && npm run coverage) || die "the front-end coverage build failed"
else
    if [ ! -f ui/coverage/lcov.info ]; then
        echo "sonar-scan-ui: warning - --skip-build and no ui/coverage/lcov.info;" >&2
        echo "  SonarQube will report 0% coverage for the front end." >&2
    fi
fi

[ -f ui/coverage/lcov.info ] ||
    echo "sonar-scan-ui: warning - ui/coverage/lcov.info is missing" >&2

# --- analysis ---------------------------------------------------------------
#
# Every path handed to the scanner is relative to ui/, because the LCOV report
# Vitest writes uses ui/-relative SF: lines (SF:src/App.tsx, not
# SF:ui/src/App.tsx). Pointing sonar.sources at ui/src from the repository root
# instead would analyse the same files and then fail to match a single coverage
# record, reporting 0% while looking entirely healthy.
#
# --network host is what lets one SONAR_URL be correct on both sides of the
# container boundary: the preflight, the token and the harvest all run on this
# machine, and only the scanner runs inside. Attaching to the compose network
# would work too but would need the URL rewritten to http://sonarqube:9000 for
# the container alone, and would break the moment SONAR_URL pointed somewhere
# else. Colima publishes the port on the VM's loopback, so a host-network
# container reaches it there.
#
# The token is passed by name and not by value: `-e SONAR_TOKEN` with no `=`
# tells docker to copy it out of this script's environment, so the secret never
# appears in the command line and therefore never in `ps` output. scan.sh keeps
# it out of `ps` the same way, by exporting it rather than passing -Dsonar.token.
#
# The container runs as the invoking user so that nothing it writes into the
# mounted worktree is left owned by the image's uid. That costs two overrides,
# because both of the image's default write targets belong to that uid:
#
#   SONAR_USER_HOME       defaults to /opt/sonar-scanner/.sonar, not writable by
#                         us. /tmp is mode 1777, so redirect the analyser cache
#                         there. It is a cache; losing it each run costs seconds.
#   SCANNER_WORKDIR_PATH  defaults to /tmp/.scannerwork, which the image creates
#                         in advance owned by scanner-cli, so we cannot write it
#                         either. Redirecting it into the mount fixes that and
#                         buys the thing we actually need: report-task.txt lands
#                         at ui/.scannerwork/report-task.txt on this side of the
#                         container boundary, where the compute engine poll below
#                         can read the task id. Left at its default the file
#                         would be written inside the container and vanish with
#                         it, and there would be no way to tell whether the
#                         numbers we harvest came from this analysis or the last.

echo "sonar-scan-ui: analysing $PROJECT_KEY"

export SONAR_TOKEN

docker run --rm \
    --platform linux/amd64 \
    --network host \
    -u "$(id -u):$(id -g)" \
    -e SONAR_HOST_URL="$SONAR_URL" \
    -e SONAR_TOKEN \
    -e SONAR_USER_HOME=/tmp/.sonar \
    -e SCANNER_WORKDIR_PATH=/usr/src/ui/.scannerwork \
    -v "$PWD:/usr/src" \
    -w /usr/src \
    "$SCANNER_IMAGE" \
    -Dsonar.projectKey="$PROJECT_KEY" \
    -Dsonar.projectName="$PROJECT_NAME" \
    -Dsonar.projectBaseDir=/usr/src/ui \
    -Dsonar.sources=src \
    -Dsonar.tests=src \
    -Dsonar.test.inclusions='**/*.test.ts,**/*.test.tsx' \
    -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info ||
    die "the scanner failed"

# --- wait for the compute engine -------------------------------------------
#
# The scanner uploads and returns; SonarQube then computes. Harvesting without
# waiting reads whatever the previous analysis left behind, which on a first run
# means an empty project and on every later run means the last commit's numbers.

task_file="ui/.scannerwork/report-task.txt"
[ -f "$task_file" ] || die "the scanner did not write $task_file"

ce_task=$(grep '^ceTaskId=' "$task_file" | cut -d= -f2) ||
    die "no ceTaskId in $task_file - the scanner wrote a task file we cannot read"
[ -n "$ce_task" ] || die "empty ceTaskId in $task_file"

echo "sonar-scan-ui: waiting for compute engine task $ce_task"

ce_status=""
for _ in $(seq 1 60); do
    ce_status=$(api "/api/ce/task?id=$ce_task" |
        python3 -c 'import sys,json; print(json.load(sys.stdin)["task"]["status"])') ||
        die "could not read the status of compute engine task $ce_task"
    case "$ce_status" in
        SUCCESS) break ;;
        FAILED | CANCELED) die "compute engine task $ce_task ended $ce_status" ;;
        *) sleep 4 ;;
    esac
done
[ "$ce_status" = SUCCESS ] ||
    die "compute engine task $ce_task did not finish in 240 s (last: $ce_status)"

# --- harvest ---------------------------------------------------------------

measures_json=$(api "/api/measures/component?component=$PROJECT_KEY&metricKeys=software_quality_reliability_issues,software_quality_maintainability_issues,software_quality_security_issues,coverage,ncloc,tests")
main_json=$(api "/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=1&scopes=MAIN&facets=impactSoftwareQualities")
test_json=$(api "/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=1&scopes=TEST&facets=impactSoftwareQualities")

echo "sonar-scan-ui: harvesting production issue inventory"

inventory_file=$(mktemp)
trap 'rm -f "$inventory_file"' EXIT

page=1
while :; do
    page_json=$(api "/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=500&scopes=MAIN&p=$page")

    # The component key prefix includes its colon, so stripping
    # "eop-threat-modeling-ui:" cannot accidentally match a Java component - and
    # the paths that come out are ui/-relative (src/App.tsx), matching the base
    # directory the scanner ran against.
    printf '%s' "$page_json" | python3 -c '
import sys, json
d = json.load(sys.stdin)
prefix = "eop-threat-modeling-ui:"
for i in d["issues"]:
    comp = i["component"]
    if comp.startswith(prefix):
        comp = comp[len(prefix):]
    qualities = ",".join(sorted(im["softwareQuality"] for im in i.get("impacts", [])))
    print("{}|{}|{}|{}".format(qualities, i["rule"], comp, i.get("hash", "-")))
' >>"$inventory_file" || die "could not parse the issue page $page returned by SonarQube"

    total=$(printf '%s' "$page_json" |
        python3 -c 'import sys,json; print(json.load(sys.stdin)["paging"]["total"])') ||
        die "could not read paging.total out of the issue page $page"

    seen=$(wc -l <"$inventory_file" | tr -d ' ')
    [ "$seen" -ge "$total" ] && break
    page=$((page + 1))
    [ "$page" -gt 20 ] && die "more than 10000 issues - paginate by component instead"
done

# --- write the report ------------------------------------------------------
#
# The temporary file is created beside the report rather than in /tmp so that mv
# is a rename within one filesystem and therefore atomic. Redirecting straight
# into $REPORT would truncate the committed file before write-report.py had run,
# so a failure would leave no report at all rather than the previous one.

source_hash=$(sonar_ui_source_hash)
file_count=$(sonar_ui_source_file_count)

report_tmp=$(mktemp "$REPORT.XXXXXX")
trap 'rm -f "$inventory_file" "$report_tmp"' EXIT

SONAR_REPORT_FLAVOUR=ui \
    SONAR_SERVER_VERSION="$server_version" \
    SONAR_SOURCE_HASH="$source_hash" \
    SONAR_FILE_COUNT="$file_count" \
    SONAR_MEASURES="$measures_json" \
    SONAR_MAIN="$main_json" \
    SONAR_TEST="$test_json" \
    SONAR_INVENTORY_FILE="$inventory_file" \
    SONAR_SCANNER_IMAGE="$SCANNER_IMAGE" \
    python3 tools/sonar/write-report.py >"$report_tmp" ||
    die "write-report.py failed - $REPORT left as it was"

mv "$report_tmp" "$REPORT"

echo "sonar-scan-ui: wrote $REPORT"

# Tighten through the same entry point CI runs, so a ceiling lowered locally and
# a ceiling checked in CI cannot be the product of two different code paths.
tools/sonar/ratchet-ui.sh --tighten
