#!/usr/bin/env bash
# Run a full SonarQube analysis against the local stack and write the two
# committed artefacts this gate reads: tools/sonar/sonar-report.json (what the
# scan found) and tools/sonar/sonar-baseline.json (the ratchet ceiling, only
# ever lowered). See ADR-060.
#
# This is the half of the gate that needs a server, and it is therefore the
# half that never runs in CI. The user requirement behind this design was
# explicit: export the report locally, commit it, compare in CI without a live
# server. So the division of labour is - this script talks to SonarQube and
# produces JSON; tools/sonar/ratchet.sh reads JSON and talks to nobody.
#
# Exit codes, the same contract the other gates in this repository use:
#   0  scan completed and the artefacts were written
#   1  a gating condition (this script does not gate; reserved so the contract
#      is uniform across tools/, and so a future check here has a code to use)
#   2  could not run - no container runtime, no server, no token, analysis
#      failed. Distinguished from 1 on purpose: "the gate says no" and "the
#      gate could not form an opinion" are different facts and a CI log that
#      conflates them wastes the reader's time.
#
# Usage:
#   docker compose -f compose.sonar.yml up -d     # once, wait for healthy
#   tools/sonar/scan.sh                           # full: verify + scan
#   tools/sonar/scan.sh --skip-build              # reuse an existing target/
#
# Environment:
#   SONAR_URL         default http://localhost:9000
#   SONAR_TOKEN       if set, used as-is; otherwise we mint one (see below)
#   SONAR_ADMIN_USER  default admin
#   SONAR_ADMIN_PASS  default admin

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=tools/sonar/source-hash.sh
source tools/sonar/source-hash.sh

SONAR_URL="${SONAR_URL:-http://localhost:9000}"
SONAR_ADMIN_USER="${SONAR_ADMIN_USER:-admin}"
SONAR_ADMIN_PASS="${SONAR_ADMIN_PASS:-admin}"
PROJECT_KEY="eop-threat-modeling"
PROJECT_NAME="Elevation of Privilege Threat Modeling"

# Pinned in the script rather than in pom.xml, and that is a load-bearing
# choice. Binding sonar-maven-plugin to a pom execution would put the scanner
# one `./mvnw verify` away from running, and this gate is deliberately NOT part
# of the local build: a developer editing one Java file should not have to wait
# on a 25 s analysis, and `verify` should not start failing because a container
# is not up. Invoking the plugin by its fully-qualified coordinates keeps the
# version exact and keeps pom.xml untouched, so the "not wired into verify"
# property is structural instead of resting on nobody adding an execution.
SCANNER_GAV="org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751"

REPORT="tools/sonar/sonar-report.json"
# No BASELINE variable here on purpose: this script never reads or writes the
# baseline itself, it delegates that entirely to ratchet.sh --tighten at the
# end. An unused path constant would read as though it did.

SKIP_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        -h | --help)
            sed -n '1,30p' "$0"
            exit 0
            ;;
        *)
            echo "sonar-scan: unknown argument '$arg'" >&2
            exit 2
            ;;
    esac
done

die() {
    echo "sonar-scan: $*" >&2
    exit 2
}

for tool in curl python3 docker; do
    command -v "$tool" >/dev/null 2>&1 || die "'$tool' is not on PATH"
done

# ---------------------------------------------------------------------------
# Preflight: is the server actually up?
# ---------------------------------------------------------------------------
# We check /api/system/status rather than just opening the port, because
# SonarQube accepts connections while Elasticsearch is still starting and
# answers DOWN or STARTING for the first minute or so. A scan submitted in that
# window fails in a way that reads like a configuration error.
status=$(curl -fsS --max-time 10 "$SONAR_URL/api/system/status" 2>/dev/null || true)
case "$status" in
    *'"status":"UP"'*) : ;;
    '')
        die "no SonarQube at $SONAR_URL. Start it with:
    colima start                                  # if the VM is not running
    docker compose -f compose.sonar.yml up -d
    docker compose -f compose.sonar.yml ps        # wait for (healthy)"
        ;;
    *) die "SonarQube at $SONAR_URL is not ready yet: $status" ;;
esac

server_version=$(printf '%s' "$status" | python3 -c 'import sys,json; print(json.load(sys.stdin)["version"])') ||
    die "could not read the version out of $SONAR_URL/api/system/status"
echo "sonar-scan: server $server_version at $SONAR_URL"

# ---------------------------------------------------------------------------
# Token
# ---------------------------------------------------------------------------
# `sonar.login`/`sonar.password` are gone from modern scanners, so a token is
# the only supported credential. We revoke-then-generate a fixed name rather
# than accumulating one token per run: token names are unique, so a plain
# generate fails on the second invocation, and the obvious workaround of a
# timestamped name leaves a growing list of live credentials on the server.
if [ -z "${SONAR_TOKEN:-}" ]; then
    echo "sonar-scan: minting a token (SONAR_TOKEN not set)"
    curl -fsS -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASS" -X POST \
        "$SONAR_URL/api/user_tokens/revoke" -d 'name=eop-local-scan' >/dev/null 2>&1 || true
    SONAR_TOKEN=$(
        curl -fsS -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASS" -X POST \
            "$SONAR_URL/api/user_tokens/generate" \
            -d 'name=eop-local-scan' -d 'type=USER_TOKEN' |
            python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
    ) || die "could not mint a token as $SONAR_ADMIN_USER. Set SONAR_TOKEN, or fix the credentials."
fi

api() {
    # Token as the basic-auth username with an empty password is SonarQube's
    # documented scheme. Note the trailing colon - without it curl prompts.
    #
    # The `|| die` is here rather than at each call site on purpose, and it is
    # what holds this script to the 0/1/2 contract in the header. `curl -f`
    # reports transport and HTTP failures in its own vocabulary - 22 for a 5xx,
    # 28 for a --max-time timeout, 7 for a refused connection - and a bare
    # `x=$(api ...)` propagates that verbatim under `set -e`. So an overloaded
    # local SonarQube used to abort this script with 22, a number the contract
    # never allocates, and 1 was reachable too: the code the header reserves for
    # a gating finding this script does not issue. Dying at the seam converts
    # every one of them to 2 and names the endpoint. It works through a command
    # substitution because `die` exits the subshell 2, the assignment then fails
    # under `set -e`, and the script exits 2 - verified rather than assumed.
    # Pipeline call sites are not covered by this (with `pipefail` the rightmost
    # non-zero status wins, so a downstream `grep -q` or python returning 1 would
    # mask the 2) and each therefore carries its own `|| die`; there are two, at
    # the token validation and the compute-engine poll, and both do. Nor would a
    # condition context be covered - `set -e` is suspended inside `if`, `&&` and
    # `||`, so `if api ...` would take the else branch on a dead server rather
    # than exiting. There is no such site today; the general rule is that the
    # syntactic context decides, and only a plain assignment or a bare call gets
    # the contract for free.
    curl -fsS --max-time 60 -u "$SONAR_TOKEN:" "$SONAR_URL$1" ||
        die "$SONAR_URL$1 did not answer - is the SonarQube container still healthy?"
}

api '/api/authentication/validate' | grep -q '"valid":true' ||
    die "the token was rejected by $SONAR_URL"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
# The full `verify`, not `test`, and this is the single most important line in
# the script for the numbers to mean anything. JaCoCo's `report` goal is bound
# to `verify`, and failsafe's integration tests merge their coverage into
# target/jacoco.exec before it runs. Scanning after a bare `mvn test` therefore
# hands SonarQube a partial coverage report - which is very probably why the
# figure that prompted this story was 68% while a post-verify scan of the same
# commit measures 95.1%. Two scans of identical source disagreeing by 27 points
# is exactly the kind of thing a committed baseline must not inherit.
if [ "$SKIP_BUILD" -eq 0 ]; then
    echo "sonar-scan: ./mvnw -B verify (this also produces the JaCoCo XML Sonar reads)"
    ./mvnw -B verify || die "the build failed; fix it before scanning"
else
    echo "sonar-scan: --skip-build, reusing target/"
    [ -f target/site/jacoco/jacoco.xml ] ||
        echo "sonar-scan: WARNING no target/site/jacoco/jacoco.xml - coverage will be absent from this scan" >&2
fi

# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------
echo "sonar-scan: submitting analysis"
# The credential goes through the environment, not through `-Dsonar.token=`.
# The scanner reads SONAR_TOKEN natively, and a `-D` would put the token in the
# process command line where any other local user can read it out of `ps` for
# the 25 s the analysis runs. Same value, same effect, no window.
export SONAR_TOKEN
./mvnw -B "$SCANNER_GAV:sonar" \
    "-Dsonar.host.url=$SONAR_URL" \
    "-Dsonar.projectKey=$PROJECT_KEY" \
    "-Dsonar.projectName=$PROJECT_NAME" ||
    die "the scanner failed"

# The scanner uploads and returns; the server then processes asynchronously.
# Querying issue counts at this point returns the PREVIOUS analysis, which is
# the single most effective way to bake a wrong baseline into a commit: the
# numbers look plausible, they are just one scan out of date. So we poll the
# Compute Engine task to completion before reading anything.
task_file="target/sonar/report-task.txt"
[ -f "$task_file" ] || die "no $task_file - the scanner did not report a task id"
# The `|| die` is load-bearing rather than defensive garnish. Without it a
# report-task.txt that exists but has no ceTaskId line leaves grep returning 1,
# which pipefail and `set -e` turn into a silent exit 1 - the code this script
# reserves for a gating finding, which it never issues. Exit 2 is the honest
# answer: the scan could not run.
ce_task=$(grep '^ceTaskId=' "$task_file" | cut -d= -f2) ||
    die "no ceTaskId in $task_file - the scanner wrote a task file we cannot read"
[ -n "$ce_task" ] || die "empty ceTaskId in $task_file"
echo "sonar-scan: waiting for compute engine task $ce_task"

ce_status=""
for _ in $(seq 1 60); do
    # `|| die` rather than letting the pipeline's own status escape: under
    # `pipefail` a curl failure surfaces as 22 and a JSON shape change as 1,
    # and 1 is the code this script's header reserves for a gating finding it
    # never issues. Both mean "the gate could not run", which is 2.
    ce_status=$(api "/api/ce/task?id=$ce_task" |
        python3 -c 'import sys,json; print(json.load(sys.stdin)["task"]["status"])') ||
        die "could not read the status of compute engine task $ce_task"
    case "$ce_status" in
        SUCCESS) break ;;
        FAILED | CANCELED) die "compute engine task $ce_task ended $ce_status" ;;
        *) sleep 4 ;;
    esac
done
[ "$ce_status" = SUCCESS ] || die "compute engine task $ce_task did not finish in 240 s (last: $ce_status)"

# ---------------------------------------------------------------------------
# Harvest
# ---------------------------------------------------------------------------
# Three whole-tree counts come from /api/measures/component. Since EOP-000
# (2026-09-02) they are recorded as context and are NOT what the gate compares -
# the ratchet is production-scoped, and the gated numbers come from the MAIN
# facet below. These are kept because they are the same numbers the project
# overview page shows, so when a developer disputes the baseline against what
# they can see in the browser, the report holds both figures and the difference
# between them is the test-code side, spelled out in scope.TEST.
measures_json=$(api "/api/measures/component?component=$PROJECT_KEY&metricKeys=software_quality_reliability_issues,software_quality_maintainability_issues,software_quality_security_issues,coverage,ncloc,tests")

# The MAIN facet is the gated quantity. The TEST facet beside it is context, and
# keeping both is what makes the narrowing auditable rather than a quiet
# deletion: MAIN + TEST must reconcile with the whole-tree measures above, so a
# reader can see exactly what the gate is declining to count.
#
# Test code is still analysed and still hashed. Excluding it from analysis would
# have bought nothing on coverage - SonarQube already classifies src/test/java
# under sonar.tests, which is why coverage reads 95.1% with test code fully in
# scope - while making 66% of the codebase invisible even in the browser. What
# needed narrowing was the gate, not the analysis. See ADR-060 as amended.
main_json=$(api "/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=1&scopes=MAIN&facets=impactSoftwareQualities")
test_json=$(api "/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=1&scopes=TEST&facets=impactSoftwareQualities")

# Production issue inventory, paginated. Scoped to MAIN because these
# fingerprints are the gated set: ratchet.sh diffs them to name what changed
# instead of only saying a number went up, so a test-code entry in this list
# would be a finding the gate reports as gated while not gating it.
#
# The filter is applied at the server rather than by matching a src/main/java
# prefix here, so "production" keeps exactly one definition - SonarQube's own
# scope classification, the same one behind the MAIN facet above - instead of a
# third copy of it in this repository alongside source-hash.sh.
#
# Sonar's own `hash` field is a digest of the line's content, so a fingerprint
# survives the issue moving up or down the file - which is what stops an
# unrelated insertion above a finding from reading as a new finding.
echo "sonar-scan: harvesting production issue inventory"
inventory_file=$(mktemp)
trap 'rm -f "$inventory_file"' EXIT
page=1
while :; do
    page_json=$(api "/api/issues/search?componentKeys=$PROJECT_KEY&resolved=false&ps=500&scopes=MAIN&p=$page")
    printf '%s' "$page_json" | python3 -c '
import sys, json
d = json.load(sys.stdin)
prefix = "eop-threat-modeling:"
for i in d["issues"]:
    comp = i["component"]
    if comp.startswith(prefix):
        comp = comp[len(prefix):]
    qualities = ",".join(sorted(im["softwareQuality"] for im in i.get("impacts", [])))
    print("{}|{}|{}|{}".format(qualities, i["rule"], comp, i.get("hash", "-")))
' >>"$inventory_file" || die "could not parse the issue page $page returned by SonarQube"
    total=$(printf '%s' "$page_json" | python3 -c 'import sys,json; print(json.load(sys.stdin)["paging"]["total"])') ||
        die "could not read paging.total from the issue page $page returned by SonarQube"
    seen=$(wc -l <"$inventory_file" | tr -d ' ')
    [ "$seen" -ge "$total" ] && break
    page=$((page + 1))
    # SonarQube refuses p*ps > 10000. We would need 20 pages of 500 to hit it;
    # failing loudly beats looping forever if this project ever gets there.
    [ "$page" -gt 20 ] && die "more than 10000 issues - paginate by component instead"
done

# ---------------------------------------------------------------------------
# Write the report
# ---------------------------------------------------------------------------
source_hash=$(sonar_source_hash)
file_count=$(sonar_source_file_count)

echo "sonar-scan: writing $REPORT"
# Write to a temporary file and move it into place, rather than redirecting
# straight at $REPORT. The shell truncates a `>` target *before* the process
# runs, so a crash inside write-report.py would leave the committed
# sonar-report.json empty in the working tree - turning a failed scan into an
# unrelated-looking dirty file the developer then has to recognise and revert.
# A move is atomic on the same filesystem, so the report is either the previous
# one or the new one, never a truncated nothing. The temporary file is created
# beside the report rather than in $TMPDIR precisely so that "same filesystem"
# is guaranteed rather than merely likely - a cross-device mv degrades to
# copy-then-unlink and loses the atomicity this is here for.
report_tmp=$(mktemp "$REPORT.XXXXXX")
trap 'rm -f "$inventory_file" "$report_tmp"' EXIT
SONAR_SERVER_VERSION="$server_version" \
    SONAR_SOURCE_HASH="$source_hash" \
    SONAR_FILE_COUNT="$file_count" \
    SONAR_MEASURES="$measures_json" \
    SONAR_MAIN="$main_json" \
    SONAR_TEST="$test_json" \
    SONAR_INVENTORY_FILE="$inventory_file" \
    SONAR_SCANNER_GAV="$SCANNER_GAV" \
    python3 tools/sonar/write-report.py >"$report_tmp" ||
    die "write-report.py failed - $REPORT left as it was"
mv "$report_tmp" "$REPORT"

# Tighten through the same entry point CI uses, rather than calling the Python
# directly. If the ratchet ever disagrees with itself between "the developer's
# run" and "the CI run", we want that to be impossible by construction rather
# than a thing we assert - so there is one command, invoked here with --tighten
# and in CI without it.
tools/sonar/ratchet.sh --tighten
