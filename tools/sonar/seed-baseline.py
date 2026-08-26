#!/usr/bin/env python3
"""Derive tools/sonar/sonar-baseline.json from a fresh scan report.

This runs once to bootstrap the gate, and thereafter only when a ceiling is
being raised deliberately under review. Day to day the baseline moves on its
own in one direction only: tools/sonar/ratchet.sh --tighten lowers it when a
scan finds fewer issues than the ceiling. Nothing lowers the count in this
script's absence and nothing raises it without a human running this file.

WHY THIS IS A SEPARATE SCRIPT AND NOT A FLAG ON ratchet.py

Because the two have opposite risk profiles and must not share a code path.
ratchet.py runs unattended in CI on every pull request; this script rewrites
the very numbers ratchet.py checks against. Had we spelled it
`ratchet.py --seed`, one mistyped argument in a workflow file would replace
the gate with a rubber stamp, and the run would go green while doing so. A
distinct filename means the dangerous operation cannot be reached by accident
from the safe one, and it makes the absence greppable: `grep -rn seed-baseline
.github/` matches only comments, never a `run:` line. State the claim that way
round rather than as "stays empty" - documenting the invariant in ci.yml is
itself enough to put the string in that directory, so the stronger phrasing
falsifies itself and invites a reader who checks to distrust the rest of this
argument. The safety property is unchanged; only the wording of the evidence is.

WHAT IT REFUSES TO DO

It will not overwrite an existing baseline without --force. That is not
politeness about clobbering a file: raising a ceiling is how a ratchet dies,
so the act needs a step that cannot be performed absent-mindedly. --force
leaves a trace in shell history, and the resulting diff shows a reviewer
exactly which counts moved and which findings were admitted. Reviewing that
diff is the actual control - this flag only ensures there is a diff to review
rather than a file that quietly agrees with today's code.

It will not seed from a stale report. The report carries the sourceHash of
the tree it was measured on; if that no longer matches the working tree, the
counts describe different code and would enshrine a ceiling for a tree nobody
has. Same reasoning as the freshness check in ratchet.py, and deliberately
the same helper, so the two cannot disagree about what "stale" means.

Exit codes match the rest of the gate: 0 wrote a baseline, 2 could not.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

QUALITIES = ("RELIABILITY", "MAINTAINABILITY", "SECURITY")

# The prose that ships inside the baseline. It is here rather than in a
# template file because the file it lands in is generated: prose maintained
# beside the generator survives, prose hand-added to the output does not.
BASELINE_COMMENT = [
    "SONARQUBE ISSUE RATCHET BASELINE. See docs/adr/ADR-060-sonarqube-issue-ratchet.md.",
    "",
    "SCOPE. The three counts below are SonarQube software-quality issue totals for the",
    "whole scanned tree - src/main/java and src/test/java together, as reported by",
    "/api/measures/component. They are a ceiling, not a target: a pull request whose",
    "scan finds more issues in any one quality than the number here fails the",
    "sonar-ratchet job. Coverage is NOT gated here; JaCoCo owns coverage in pom.xml,",
    "and the coverage figure in the scan report is context only.",
    "",
    "KEY FORMAT. Each entry in issues[] is QUALITIES|rule|path|hash, where QUALITIES is",
    "the issue's software qualities comma-joined and sorted, path is repo-relative, and",
    "hash is SonarQube's digest of the offending line's CONTENT - not its number - so a",
    "finding survives edits elsewhere in the file. The list is a sorted multiset:",
    "duplicates are kept deliberately, because the same rule can fire twice on two",
    "identical lines in one file and set semantics would let a new occurrence hide",
    "behind an existing one.",
    "",
    "WHAT THE LIST IS FOR. It is diagnostic, not the gate. Only counts[] is compared",
    "for pass or fail; issues[] exists so that a failure can name the findings that",
    "were added rather than reporting that a number went up by one. Do not hand-edit",
    "it to silence a finding - that changes nothing about the outcome and desynchronises",
    "the diagnosis from the counts.",
    "",
    "PREFER THE FIX. Raising a number here is always available and almost never right.",
    "The issues are real findings; the ratchet exists so that the total can only fall.",
    "If a story genuinely must admit a new issue, raise the ceiling in the same commit",
    "as the code, and say in the commit message which rule fired and why living with it",
    "beats fixing it. A raise with no argument is a raise a reviewer should reject.",
    "",
    "A RAISED CEILING IS A LIABILITY, NOT A DISMISSAL. The gate is one-directional by",
    "design: tools/sonar/ratchet.sh --tighten lowers these numbers automatically when a",
    "scan finds fewer issues, so a ceiling left high after the underlying issues are",
    "fixed does not stay high by accident. It stays high only if nobody rescans.",
    "",
    "GENERATED FILE. Written by tools/sonar/seed-baseline.py and updated in place by",
    "tools/sonar/ratchet.sh --tighten. Both preserve this comment. Hand-editing the",
    "counts is legitimate when raising a ceiling under review; hand-editing anything",
    "else is not, and hand-editing the report next door defeats the freshness check",
    "rather than passing it.",
]


def _fail(message: str) -> None:
    sys.stderr.write(f"seed-baseline: {message}\n")
    raise SystemExit(2)


def _counts(report: dict[str, Any]) -> dict[str, int]:
    raw = report.get("counts")
    if not isinstance(raw, dict):
        _fail("the scan report has no counts object. Regenerate it with tools/sonar/scan.sh.")
    out: dict[str, int] = {}
    for quality in QUALITIES:
        value = raw.get(quality)
        # A missing quality is not read as zero. Zero is a real and achievable
        # count - SECURITY is genuinely 0 today - so treating absence as zero
        # would seed the strictest possible ceiling from a truncated report and
        # fail every subsequent scan for reasons nobody could see.
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            _fail(
                f"the scan report's count for {quality} is {value!r}, which is not a "
                "non-negative integer. Regenerate the report rather than editing it."
            )
        out[quality] = value
    return out


def _actual_source_hash(repo_root: Path) -> str:
    """Recompute the freshness token by asking the shell library for it.

    Shelling out rather than reimplementing the pipeline, for exactly the reason
    tools/sonar/ratchet.sh gives: two definitions of the token would agree until
    they did not, and the symptom would be a bootstrap refused as stale on a
    tree scanned seconds earlier.
    """
    library = repo_root / "tools" / "sonar" / "source-hash.sh"
    if not library.exists():
        _fail(f"the source-hash library is missing at {library}.")
    try:
        result = subprocess.run(
            ["bash", "-c", f'set -euo pipefail; . "{library}"; sonar_source_hash'],
            capture_output=True,
            text=True,
            check=True,
            cwd=repo_root,
        )
    except subprocess.CalledProcessError as exc:
        _fail(f"could not compute the source hash: {exc.stderr.strip() or exc}")
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default="tools/sonar/sonar-report.json")
    parser.add_argument("--baseline", default="tools/sonar/sonar-baseline.json")
    parser.add_argument(
        "--force",
        action="store_true",
        help="overwrite an existing baseline - raises the ceiling, so review the diff",
    )
    args = parser.parse_args()

    repo_root = Path(
        subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    )
    os.chdir(repo_root)

    report_path = Path(args.report)
    baseline_path = Path(args.baseline)

    if not report_path.exists():
        _fail(
            f"no scan report at {report_path}. Start the local stack with\n"
            "  docker compose -f compose.sonar.yml up -d\n"
            "then run tools/sonar/scan.sh."
        )

    if baseline_path.exists() and not args.force:
        _fail(
            f"a baseline already exists at {baseline_path}, and overwriting it raises the\n"
            "  ceiling rather than lowering it. If a scan found FEWER issues, you want\n"
            "  tools/sonar/ratchet.sh --tighten instead, which lowers the ceiling and needs\n"
            "  no flag. If you really are admitting new issues, re-run with --force and say\n"
            "  in the commit message which rule fired and why living with it beats fixing it."
        )

    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        _fail(f"{report_path} is not valid JSON: {exc}")

    reported_hash = report.get("sourceHash")
    if not isinstance(reported_hash, str) or not reported_hash:
        _fail(f"{report_path} carries no sourceHash. Regenerate it with tools/sonar/scan.sh.")

    actual_hash = _actual_source_hash(repo_root)
    if actual_hash != reported_hash:
        _fail(
            "the scan report is stale - it was measured on a different tree, so its counts\n"
            f"  would seed a ceiling for code nobody has.\n"
            f"    report: {reported_hash}\n"
            f"    actual: {actual_hash}\n"
            "  Re-run tools/sonar/scan.sh, then seed from the fresh report."
        )

    issues = report.get("issues")
    if not isinstance(issues, list):
        _fail(f"{report_path} has no issues array. Regenerate it with tools/sonar/scan.sh.")

    baseline = {
        "_comment": BASELINE_COMMENT,
        "counts": _counts(report),
        # Provenance, so a reader of a raised ceiling can find the scan it came
        # from. Deliberately not the sourceHash: a baseline is not tied to one
        # tree the way a report is, and recording a hash here would read as a
        # freshness claim the baseline does not make and must not make - it is a
        # ceiling that outlives the code it was measured against.
        "seededFrom": {
            "generatedAt": report.get("generatedAt"),
            "sonarQubeVersion": report.get("sonarQubeVersion"),
            "scannerGav": report.get("scannerGav"),
        },
        "issues": sorted(str(entry) for entry in issues),
    }

    # Same dump settings as write-report.py and ratchet.py, so the three cannot
    # produce diffs that differ only in formatting.
    with baseline_path.open("w", encoding="utf-8") as handle:
        json.dump(baseline, handle, indent=2, sort_keys=True, separators=(",", ": "))
        handle.write("\n")

    counts = baseline["counts"]
    verb = "raised" if args.force else "seeded"
    sys.stderr.write(
        f"seed-baseline: {verb} {baseline_path} from {report_path}\n"
        f"  RELIABILITY {counts['RELIABILITY']}  "
        f"MAINTAINABILITY {counts['MAINTAINABILITY']}  "
        f"SECURITY {counts['SECURITY']}\n"
        f"  {len(baseline['issues'])} fingerprints recorded for diagnosis\n"
        "  Read the diff before committing - this file is the gate's ceiling.\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
