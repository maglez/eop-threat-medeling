#!/usr/bin/env bash
# Unit tests for the CVE gating logic in scan-dependencies.sh.
#
# The gate that decides whether a HIGH or CRITICAL Trivy finding blocks the
# build is an embedded python heredoc inside scan-dependencies.sh, taking two
# arguments: a Trivy JSON report and an allowlist. This harness EXTRACTS that
# heredoc from the shipped script and runs it against synthetic fixtures, so
# every assertion here is made against the code that actually ships.
#
# Extraction rather than duplication is the load-bearing choice. Its sibling
# test-audit-plugins.sh pastes a copy of audit-plugins.sh's python into itself,
# which means breaking the shipped script leaves that harness green -- a test
# of a copy is not a test of the control. Extracting has the opposite property:
# break the gate in scan-dependencies.sh and these tests go red. That is the
# EOP-147 acceptance criterion, and it is why the two harnesses differ.
#
# Hermetic by construction: no trivy, no network, no real install. Fixtures are
# hand-written Trivy documents and fixture allowlists. The real
# accepted-cves.json is deliberately NOT used -- it carries live entries, so
# pairing it with a findings-free report would trip the anti-rot branch and the
# test would be measuring the allowlist rather than the gate. Expiry dates are
# far future (2099-12-31) or far past (2020-01-01) so no case ages into failure.
#
# Exit: 0 = all tests passed, 1 = one or more tests failed.
# Run:  tools/supply-chain/test-scan-dependencies.sh

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

source_script="tools/supply-chain/scan-dependencies.sh"
workdir=".tmp/test-scan-dependencies"

rm -rf "$workdir"
mkdir -p "$workdir"

pass=0
fail=0

# ---------------------------------------------------------------------------
# Extract the gate from the shipped script.
#
# scan-dependencies.sh contains TWO python heredocs, both opened with <<'PY':
# the gate, and a purely informational build-time-dependency diff that always
# exits 0. Taking both would produce a script that redefines names and never
# fails, so only the FIRST is captured -- from the first line opening the
# heredoc up to the next line that is exactly PY.
# ---------------------------------------------------------------------------
awk '
  /<<.PY./ && !seen { seen = 1; capture = 1; next }
  capture && $0 == "PY" { exit }
  capture { print }
' "$source_script" > "$workdir/gate.py"

# Guard the extraction itself. A silent awk miss would leave an empty file that
# every case would "pass" against, which is the classic vacuous-test failure.
extracted_lines=$(wc -l < "$workdir/gate.py" | tr -d ' ')
if [[ "$extracted_lines" -lt 50 ]]; then
    echo "FATAL: extracted only $extracted_lines lines from $source_script."
    echo "The gate heredoc was not found where expected. Check that the marker"
    echo "is still <<'PY' and that the gate is still the first such block."
    exit 1
fi
for marker in REQUIRED_FIELDS 'sys.exit(1)' 'no longer reported by the scan'; do
    if ! grep -qF "$marker" "$workdir/gate.py"; then
        echo "FATAL: extracted block does not contain '$marker'."
        echo "Either the wrong heredoc was captured or the gate has been rewritten."
        exit 1
    fi
done
# The informational second block prints this line. Its presence means the awk
# swallowed both heredocs, which would make every failure case exit 0.
if grep -qF 'Build-time dependencies' "$workdir/gate.py"; then
    echo "FATAL: extracted block reaches the informational second heredoc."
    exit 1
fi

echo "=== scan-dependencies.sh CVE gating tests ==="
echo "Gate extracted from $source_script ($extracted_lines lines)"
echo

# ---------------------------------------------------------------------------
# run_case <label> <report> <allowlist> <expected_exit> <expected_substring>
#
# Asserts the exit code AND that the operative message appears in the output.
# The message assertion is not decoration: a gate that exits 1 for the wrong
# reason is a gate that will be misdiagnosed, and an exit code alone cannot
# tell a missing allowlist entry from an expired one.
# ---------------------------------------------------------------------------
run_case() {
    local label="$1" report="$2" allowlist="$3" expected_exit="$4" expected_text="$5"
    local actual_exit=0

    python3 "$workdir/gate.py" "$report" "$allowlist" \
        >"$workdir/out.txt" 2>"$workdir/err.txt" || actual_exit=$?

    local problems=()
    if [[ "$actual_exit" -ne "$expected_exit" ]]; then
        problems+=("expected exit $expected_exit, got $actual_exit")
    fi
    if ! grep -qF "$expected_text" "$workdir/out.txt" "$workdir/err.txt"; then
        problems+=("output does not contain '$expected_text'")
    fi

    if [[ "${#problems[@]}" -eq 0 ]]; then
        pass=$(( pass + 1 ))
        echo "  PASS  $label"
    else
        fail=$(( fail + 1 ))
        echo "  FAIL  $label"
        local problem
        for problem in "${problems[@]}"; do
            echo "          $problem"
        done
        echo "        --- stdout ---"
        sed 's/^/        /' "$workdir/out.txt"
        echo "        --- stderr ---"
        sed 's/^/        /' "$workdir/err.txt"
    fi
}

# ---------------------------------------------------------------------------
# Fixtures: Trivy reports
# ---------------------------------------------------------------------------

cat > "$workdir/report-high.json" <<'JSON'
{
  "Results": [
    {
      "Target": "target/eop-threat-modeling.jar",
      "Type": "jar",
      "Vulnerabilities": [
        {
          "VulnerabilityID": "CVE-2099-0001",
          "PkgName": "org.example:widget",
          "Severity": "HIGH",
          "InstalledVersion": "1.0.0",
          "FixedVersion": "1.0.1",
          "Title": "Synthetic high finding for the gate tests",
          "PrimaryURL": "https://example.invalid/CVE-2099-0001"
        }
      ]
    }
  ]
}
JSON

cat > "$workdir/report-rescored-critical.json" <<'JSON'
{
  "Results": [
    {
      "Target": "target/eop-threat-modeling.jar",
      "Type": "jar",
      "Vulnerabilities": [
        {
          "VulnerabilityID": "CVE-2099-0001",
          "PkgName": "org.example:widget",
          "Severity": "CRITICAL",
          "InstalledVersion": "1.0.0",
          "FixedVersion": "1.0.1",
          "Title": "Re-scored from HIGH to CRITICAL",
          "PrimaryURL": "https://example.invalid/CVE-2099-0001"
        }
      ]
    }
  ]
}
JSON

cat > "$workdir/report-medium-only.json" <<'JSON'
{
  "Results": [
    {
      "Target": "ui/package-lock.json",
      "Type": "npm",
      "Vulnerabilities": [
        {
          "VulnerabilityID": "CVE-2099-0009",
          "PkgName": "left-pad",
          "Severity": "MEDIUM",
          "InstalledVersion": "1.0.0",
          "FixedVersion": "",
          "Title": "Synthetic medium finding, deliberately not gated",
          "PrimaryURL": "https://example.invalid/CVE-2099-0009"
        }
      ]
    }
  ]
}
JSON

cat > "$workdir/report-clean.json" <<'JSON'
{
  "Results": [
    {
      "Target": "target/eop-threat-modeling.jar",
      "Type": "jar"
    }
  ]
}
JSON

cat > "$workdir/report-no-targets.json" <<'JSON'
{
  "Results": []
}
JSON

# ---------------------------------------------------------------------------
# Fixtures: allowlists
# ---------------------------------------------------------------------------

cat > "$workdir/allowlist-empty.json" <<'JSON'
{
  "advisories": {}
}
JSON

cat > "$workdir/allowlist-active.json" <<'JSON'
{
  "advisories": {
    "CVE-2099-0001@org.example:widget": {
      "module": "org.example:widget",
      "severity": "HIGH",
      "installed": "1.0.0",
      "fixed_version": "1.0.1",
      "title": "Synthetic high finding for the gate tests",
      "introduced_by": "a synthetic fixture, not a real dependency",
      "reachability": "unreachable: the fixture package is not on any classpath, so no deployed request can reach it",
      "no_fix_available": false,
      "reviewed": "2026-09-04",
      "reviewed_under": "EOP-147",
      "expires": "2099-12-31"
    }
  }
}
JSON

cat > "$workdir/allowlist-module-mismatch.json" <<'JSON'
{
  "advisories": {
    "CVE-2099-0001@org.example:widget": {
      "module": "org.example:some-other-widget",
      "severity": "HIGH",
      "installed": "1.0.0",
      "fixed_version": "1.0.1",
      "title": "Synthetic high finding for the gate tests",
      "introduced_by": "a synthetic fixture, not a real dependency",
      "reachability": "unreachable: the fixture package is not on any classpath, so no deployed request can reach it",
      "no_fix_available": false,
      "reviewed": "2026-09-04",
      "reviewed_under": "EOP-147",
      "expires": "2099-12-31"
    }
  }
}
JSON

cat > "$workdir/allowlist-expired.json" <<'JSON'
{
  "advisories": {
    "CVE-2099-0001@org.example:widget": {
      "module": "org.example:widget",
      "severity": "HIGH",
      "installed": "1.0.0",
      "fixed_version": "1.0.1",
      "title": "Synthetic high finding for the gate tests",
      "introduced_by": "a synthetic fixture, not a real dependency",
      "reachability": "unreachable: the fixture package is not on any classpath, so no deployed request can reach it",
      "no_fix_available": false,
      "reviewed": "2020-01-01",
      "reviewed_under": "EOP-147",
      "expires": "2020-01-01"
    }
  }
}
JSON

cat > "$workdir/allowlist-bad-date.json" <<'JSON'
{
  "advisories": {
    "CVE-2099-0001@org.example:widget": {
      "module": "org.example:widget",
      "severity": "HIGH",
      "installed": "1.0.0",
      "fixed_version": "1.0.1",
      "title": "Synthetic high finding for the gate tests",
      "introduced_by": "a synthetic fixture, not a real dependency",
      "reachability": "unreachable: the fixture package is not on any classpath, so no deployed request can reach it",
      "no_fix_available": false,
      "reviewed": "2026-09-04",
      "reviewed_under": "EOP-147",
      "expires": "next Tuesday"
    }
  }
}
JSON

cat > "$workdir/allowlist-missing-fields.json" <<'JSON'
{
  "advisories": {
    "CVE-2099-0001@org.example:widget": {
      "module": "org.example:widget",
      "severity": "HIGH",
      "installed": "1.0.0",
      "fixed_version": "1.0.1",
      "title": "Synthetic high finding for the gate tests",
      "expires": "2099-12-31"
    }
  }
}
JSON

# ---------------------------------------------------------------------------
# The cases
# ---------------------------------------------------------------------------

echo "--- A gating finding must be accounted for ---"

run_case "unlisted HIGH finding fails the gate" \
    "$workdir/report-high.json" \
    "$workdir/allowlist-empty.json" \
    1 \
    "with no entry in"

run_case "correctly allowlisted finding passes and is reported as suppressed" \
    "$workdir/report-high.json" \
    "$workdir/allowlist-active.json" \
    0 \
    "suppressed until"

echo
echo "--- An entry must still describe the finding it suppresses ---"

run_case "module mismatch fails the gate" \
    "$workdir/report-high.json" \
    "$workdir/allowlist-module-mismatch.json" \
    1 \
    "allowlist says module"

run_case "re-scored severity fails the gate" \
    "$workdir/report-rescored-critical.json" \
    "$workdir/allowlist-active.json" \
    1 \
    "allowlist says severity"

run_case "entry missing required fields fails the gate" \
    "$workdir/report-high.json" \
    "$workdir/allowlist-missing-fields.json" \
    1 \
    "missing required field(s)"

echo
echo "--- An entry must expire, and must expire on a real date ---"

run_case "expired suppression fails the gate" \
    "$workdir/report-high.json" \
    "$workdir/allowlist-expired.json" \
    1 \
    "the suppression expired on"

run_case "unparseable expiry date fails the gate" \
    "$workdir/report-high.json" \
    "$workdir/allowlist-bad-date.json" \
    1 \
    "which is not a YYYY-MM-DD date"

echo
echo "--- The anti-rot direction: an entry nothing reports any more ---"

run_case "allowlisted advisory no longer reported fails the gate" \
    "$workdir/report-clean.json" \
    "$workdir/allowlist-active.json" \
    1 \
    "DELETE the entry"

echo
echo "--- Controls: what must NOT fail ---"

run_case "MEDIUM finding with no entry passes, and is reported not suppressed" \
    "$workdir/report-medium-only.json" \
    "$workdir/allowlist-empty.json" \
    0 \
    "Not gated"

run_case "clean report with an empty allowlist passes" \
    "$workdir/report-clean.json" \
    "$workdir/allowlist-empty.json" \
    0 \
    "clean, or fully accounted for"

echo
echo "--- A scan that analysed nothing is not a clean scan ---"

run_case "no analysed manifests fails rather than reporting clean" \
    "$workdir/report-no-targets.json" \
    "$workdir/allowlist-empty.json" \
    1 \
    "analysed no dependency manifests at all"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

total=$(( pass + fail ))
echo
echo "=== Results: $pass/$total passed ==="

if [[ "$fail" -gt 0 ]]; then
    echo "FAIL: $fail test(s) failed"
    exit 1
fi

echo "PASS: all tests passed"
