#!/usr/bin/env bash
#
# Supply-chain audit for the OpenCode plugin roster.
#
# Why this exists: SETUP.md used to carry a prose instruction to re-run `npm audit`
# "whenever a pin moves". Over four pin movements it was honoured once, and only
# because a human asked -- one of those movements was a commit that edited the very
# paragraph recording the trigger as unhonoured, without honouring it. Prose does not
# run. This script does.
#
# What it checks, in order of how much it would actually tell you:
#   1. Roster drift    -- every spec in opencode.json has a baseline entry, and vice
#                         versa. Without this, a newly added plugin would skip every
#                         check below and the audit would pass while covering less.
#   2. Maintainers     -- an npm account handoff is the loudest single compromise
#                         signal there is; it preceded most well-known takeovers.
#   3. Provenance      -- whether the pinned version carries an SLSA attestation.
#                         Absence is tolerated (half the roster lacks it); CHANGE is
#                         not, in either direction, because the pins are exact and a
#                         published version's attestation is immutable.
#   4. Advisories      -- `npm audit` at --audit-level=high, plus registry signature
#                         verification over the whole transitive tree.
#
# What it does NOT check, and must never be described as checking: whether a release
# has been backdoored. Advisories answer "is there a published CVE", signatures answer
# "did this tarball come from the registry unmodified", provenance answers "was it
# built by the named repository's CI". None of them answers "is the code benign", and
# that is the failure mode that matters for plugins running unsandboxed inside
# OpenCode with sight of every message and file.
#
# Usage:  tools/supply-chain/audit-plugins.sh
# Exit:   0 = clean, 1 = drift or a high/critical advisory, 2 = could not run.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

config=".opencode/opencode.json"
baseline="tools/supply-chain/expected-plugins.json"
# Inside the worktree on purpose: .tmp/ is gitignored and needs no external_directory
# grant, unlike /tmp. See AGENTS.md.
workdir=".tmp/supply-chain"

command -v npm >/dev/null 2>&1 || { echo "FATAL: npm is not on PATH"; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "FATAL: python3 is not on PATH"; exit 2; }
[[ -f "$config" ]] || { echo "FATAL: $config not found"; exit 2; }
[[ -f "$baseline" ]] || { echo "FATAL: $baseline not found"; exit 2; }

rm -rf "$workdir"
mkdir -p "$workdir"

echo "=== Plugin specs declared in $config ==="
python3 - "$config" "$workdir/specs.txt" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
# A plugin entry is either "name@version" or ["name@version", {options}].
specs = [p if isinstance(p, str) else p[0] for p in cfg.get("plugin", [])]
if not specs:
    sys.exit("FATAL: no plugin specs found -- has the config shape changed?")
for s in specs:
    if "@" not in s.lstrip("@"):
        sys.exit(f"FATAL: plugin spec is not version-locked: {s!r}. "
                 "An unpinned spec resolves to whatever npm 'latest' is at load time, "
                 "which is exactly what this audit exists to prevent.")
    print(s)
open(sys.argv[2], "w").write("\n".join(specs) + "\n")
PY

echo
echo "=== Registry metadata for each pinned spec ==="
# One `npm view` per spec, cached to disk so the comparison step is pure.
while read -r spec; do
    [[ -z "$spec" ]] && continue
    safe="${spec//\//_}"
    if ! npm view "$spec" --json > "$workdir/view-$safe.json" 2>"$workdir/view-$safe.err"; then
        echo "FATAL: npm view failed for $spec"
        cat "$workdir/view-$safe.err"
        exit 2
    fi
    echo "  fetched $spec"
done < "$workdir/specs.txt"

python3 - "$baseline" "$workdir" <<'PY'
import json, os, sys

baseline_path, workdir = sys.argv[1], sys.argv[2]
expected = json.load(open(baseline_path))["plugins"]
specs = [l for l in open(os.path.join(workdir, "specs.txt")).read().split("\n") if l]

failures, notes = [], []

# --- 1. roster drift -------------------------------------------------------
declared = {}
for spec in specs:
    at = spec.rindex("@")
    declared[spec[:at]] = spec[at + 1:]

for name in sorted(set(declared) - set(expected)):
    failures.append(
        f"{name}: declared in opencode.json but absent from the baseline. A plugin "
        f"with no baseline entry is a plugin nothing checks -- add it to "
        f"{baseline_path} in the same commit that adds the pin."
    )
for name in sorted(set(expected) - set(declared)):
    failures.append(
        f"{name}: present in the baseline but no longer declared in opencode.json. "
        f"If the plugin was removed deliberately, remove its baseline entry too."
    )

# --- 2 & 3. maintainers and provenance ------------------------------------
for name in sorted(set(declared) & set(expected)):
    version = declared[name]
    view = json.load(open(os.path.join(workdir, f"view-{(name + '@' + version).replace('/', '_')}.json")))
    exp = expected[name]

    actual_maintainers = sorted(
        m if isinstance(m, str) else m.get("name", "?") for m in view.get("maintainers", [])
    )
    if actual_maintainers != sorted(exp["maintainers"]):
        failures.append(
            f"{name}: MAINTAINER CHANGE.\n"
            f"      expected: {sorted(exp['maintainers'])}\n"
            f"      registry: {actual_maintainers}\n"
            f"      This is the strongest compromise signal npm offers. Do not update "
            f"the baseline to clear it until a human has established that the handoff "
            f"was legitimate."
        )

    has_provenance = bool(view.get("dist", {}).get("attestations"))
    if has_provenance != exp["provenance"]:
        direction = "GAINED" if has_provenance else "LOST"
        failures.append(
            f"{name}@{version}: provenance {direction} relative to the baseline "
            f"(expected {exp['provenance']}, registry {has_provenance}). The pin is an "
            f"exact version and a published version's attestation is immutable, so "
            f"either direction means the version was republished. Investigate before "
            f"updating the baseline."
        )
    elif not has_provenance:
        notes.append(f"{name}@{version}: no SLSA provenance attestation (known, accepted)")

print("\n=== Maintainer and provenance comparison ===")
for n in notes:
    print(f"  note: {n}")
attested = sum(1 for n in declared if expected.get(n, {}).get("provenance"))
print(f"  {attested} of {len(declared)} pinned plugins carry a provenance attestation")

if failures:
    print("\n=== DRIFT DETECTED ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("  no drift against the baseline")
PY

echo
echo "=== Advisory and signature audit over the pinned tree ==="
# A throwaway manifest, deliberately NOT .opencode/package.json -- that file declares
# only the @opencode-ai/plugin typings used to author .opencode/plugins/graphify.js,
# so auditing it would audit the authoring toolchain and not the plugins that load.
python3 - "$workdir/specs.txt" "$workdir/package.json" <<'PY'
import json, sys
deps = {}
for spec in open(sys.argv[1]).read().split():
    at = spec.rindex("@")
    deps[spec[:at]] = spec[at + 1:]
json.dump(
    {
        "name": "eop-plugin-supply-chain-audit",
        "version": "0.0.0",
        "private": True,
        "description": "Generated by tools/supply-chain/audit-plugins.sh. Not a real package.",
        "dependencies": deps,
    },
    open(sys.argv[2], "w"),
    indent=2,
)
PY

# --ignore-scripts mirrors how OpenCode itself installs plugins: Npm.add() drives
# @npmcli/arborist in-process with ignoreScripts: true, so no lifecycle script runs
# on the real install path either. Verified against anomalyco/opencode at v1.18.19.
( cd "$workdir" && npm install --ignore-scripts --no-fund --no-audit --loglevel=error )

audit_status=0
( cd "$workdir" && npm audit --audit-level=high ) || audit_status=$?

echo
echo "=== Registry signature verification ==="
# Needs a real install: with only a lockfile npm reports "found no dependencies to
# audit that were installed from a supported registry".
( cd "$workdir" && npm audit signatures )

if [[ "$audit_status" -ne 0 ]]; then
    echo
    echo "=== FAIL: npm audit reported a high or critical advisory ==="
    echo "Low and moderate findings do not fail this job; SETUP.md records the"
    echo "accepted residual and the reasoning. A high or critical finding needs a"
    echo "decision, not a baseline update."
    exit 1
fi

echo
echo "=== PASS: no roster drift, no maintainer change, no high/critical advisory ==="
