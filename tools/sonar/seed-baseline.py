#!/usr/bin/env python3
"""Derive a SonarQube ratchet baseline from a fresh scan report.

This runs once to bootstrap the gate, and thereafter only when a ceiling is
being raised deliberately under review. Day to day the baseline moves on its
own in one direction only: tools/sonar/ratchet.sh --tighten lowers it when a
scan finds fewer issues than the ceiling. Nothing lowers the count in this
script's absence and nothing raises it without a human running this file.

TWO PROJECTS, ONE SEEDER

There are two SonarQube projects and therefore two baselines - the Java one at
tools/sonar/sonar-baseline.json and the front-end one at
tools/sonar/sonar-ui-baseline.json (ADR-063). --flavour selects between them,
and it changes five things: which prose ships in _comment, which function in
tools/sonar/source-hash.sh computes the freshness token, whether provenance
records the Maven GAV or the pinned scanner image, and the default report and
baseline paths. --report and --baseline can still be overridden, but they
default per flavour rather than to the Java pair, so `--flavour ui` alone
cannot write front-end prose into the Java ceiling. Cross-wiring the two by
hand fails anyway: the Java hash and the UI hash cover disjoint file sets, so
seeding either project from the other's report is refused as stale.

The alternative was a second seed-baseline-ui.py. It was rejected for the
reason tools/sonar/source-hash.sh gives about the hash itself - the dangerous
logic here is the refusal to overwrite and the refusal to seed from a stale
report, and two copies of a refusal is one copy that can rot unnoticed while
the other is maintained.

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
#
# JAVA_BASELINE_COMMENT is reproduced verbatim inside the committed
# tools/sonar/sonar-baseline.json, so editing a line here means the next
# --tighten rewrites that file. That is harmless - ratchet.py preserves
# _comment untouched and nothing compares it - but it does mean a prose-only
# edit shows up in a diff nobody asked for, so leave it alone unless the prose
# has actually gone wrong.
JAVA_BASELINE_COMMENT = [
    "SONARQUBE ISSUE RATCHET BASELINE. See docs/adr/ADR-060-sonarqube-issue-ratchet.md.",
    "",
    "SCOPE. The three counts below are SonarQube software-quality issue totals for",
    "PRODUCTION CODE ONLY - SonarQube's MAIN scope, which is src/main/java - as reported",
    "by the scopes=MAIN facet of /api/issues/search. Since 2026-09-02 (EOP-000, ADR-060",
    "as amended) they are NOT whole-tree totals and are NOT the numbers the SonarQube",
    "project overview page shows; the whole-tree figure and the test-code side are both",
    "recorded in sonar-report.json under scope.ALL and scope.TEST. Test code is still",
    "analysed and still hashed - only the gate narrowed. They are a ceiling, not a",
    "target: a pull request whose scan finds more issues in any one quality than the",
    "number here fails the sonar-ratchet job. Coverage is NOT gated here; JaCoCo owns",
    "coverage in pom.xml, and the coverage figure in the scan report is context only.",
    "",
    "WHY PRODUCTION CODE ONLY. The reason was headroom, not coverage, and stating it as",
    "coverage gets it backwards. SonarQube already classified src/test/java under",
    "sonar.tests, so coverage read 95.1% with test code fully in scope before the",
    "narrowing and reads 95.1% after it - the change moved no coverage figure at all.",
    "What it moved was the gate: 211 of the 243 findings then reported sat in test code,",
    "against a ceiling carrying no headroom, so adding a routine new test file turned red",
    "a gate that had nothing to say about the product. Test-code findings are still",
    "measured and still recorded - sonar-report.json carries them under scope.TEST, and",
    "scope.MAIN plus scope.TEST reconciles with scope.ALL per quality, which is what makes",
    "the narrowing auditable from the file rather than merely asserted in prose. They",
    "simply never gate, so a green sonar-ratchet says nothing whatever about src/test/java.",
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

# The front-end baseline's prose. Deliberately a separate list rather than the
# Java text with two words swapped: almost every paragraph above says something
# true only of the Java project - src/main/java, JaCoCo, pom.xml, the MAIN
# narrowing of 2026-09-02 - and a shared list with conditional sentences in it
# would be harder to keep honest than two lists that each say one thing.
UI_BASELINE_COMMENT = [
    "SONARQUBE ISSUE RATCHET BASELINE - FRONT END.",
    "See docs/adr/ADR-063-sonarqube-frontend-project.md.",
    "",
    "SCOPE. The three counts below are SonarQube software-quality issue totals for the",
    "front-end production code in ui/src, as reported by the scopes=MAIN facet of",
    "/api/issues/search on the SEPARATE project eop-threat-modeling-ui. They have",
    "nothing to say about Java: that project has its own ceiling next door in",
    "sonar-baseline.json, and the two gates fail independently. Test code under",
    "ui/src/**/*.test.ts(x) is analysed and recorded in sonar-ui-report.json under",
    "scope.TEST, and is not gated - the same split ADR-060 made for Java, for the same",
    "reason: a routine new test file must not redden a gate that has nothing to say",
    "about the product.",
    "",
    "WHY A SECOND PROJECT AND NOT ONE POLYGLOT PROJECT. Because one set of counts over",
    "both languages would let a front-end regression be paid for out of Java headroom,",
    "and a Java fix would silently create room for a TypeScript issue. The number would",
    "still go up and down truthfully while meaning nothing about either population.",
    "",
    "KEY FORMAT. Each entry in issues[] is QUALITIES|rule|path|hash, where QUALITIES is",
    "the issue's software qualities comma-joined and sorted, path is relative to the",
    "scanner's base directory - ui/ - so it reads src/App.tsx rather than",
    "ui/src/App.tsx, and hash is SonarQube's digest of the offending line's CONTENT,",
    "not its number, so a finding survives edits elsewhere in the file. The list is a",
    "sorted multiset: duplicates are kept deliberately, because the same rule can fire",
    "twice on two identical lines in one file and set semantics would let a new",
    "occurrence hide behind an existing one.",
    "",
    "WHAT THE LIST IS FOR. It is diagnostic, not the gate. Only counts[] is compared",
    "for pass or fail; issues[] exists so that a failure can name the findings that",
    "were added rather than reporting that a number went up by one. Do not hand-edit",
    "it to silence a finding - that changes nothing about the outcome and desynchronises",
    "the diagnosis from the counts.",
    "",
    "COVERAGE IS NOT GATED HERE, AND THERE IS NO EQUIVALENT OF JaCoCo. The front-end",
    "figure in sonar-ui-report.json comes from ui/coverage/lcov.info via Vitest's v8",
    "provider and is context only. ADR-063 declines to add a coverage threshold to",
    "ui/vite.config.ts as well: the ratchet already holds quality, and two numbers for",
    "one invariant is how the two drift apart.",
    "",
    "PREFER THE FIX. Raising a number here is always available and almost never right.",
    "The issues are real findings; the ratchet exists so that the total can only fall.",
    "If a story genuinely must admit a new issue, raise the ceiling in the same commit",
    "as the code, and say in the commit message which rule fired and why living with it",
    "beats fixing it. A raise with no argument is a raise a reviewer should reject.",
    "",
    "A RAISED CEILING IS A LIABILITY, NOT A DISMISSAL. The gate is one-directional by",
    "design: tools/sonar/ratchet-ui.sh --tighten lowers these numbers automatically when",
    "a scan finds fewer issues, so a ceiling left high after the underlying issues are",
    "fixed does not stay high by accident. It stays high only if nobody rescans.",
    "",
    "GENERATED FILE. Written by tools/sonar/seed-baseline.py --flavour ui and updated in",
    "place by tools/sonar/ratchet-ui.sh --tighten. Both preserve this comment.",
    "Hand-editing the counts is legitimate when raising a ceiling under review;",
    "hand-editing anything else is not, and hand-editing the report next door defeats",
    "the freshness check rather than passing it.",
]

# Everything that differs between the two projects, in one table so that the
# difference is enumerable rather than scattered through the control flow. A
# third flavour would be a row here, not a new branch to find in four places.
FLAVOURS: dict[str, dict[str, Any]] = {
    "java": {
        "comment": JAVA_BASELINE_COMMENT,
        # The name of the shell function in tools/sonar/source-hash.sh, not a
        # Python callable: the token is computed by that library and only by
        # that library, so both flavours shell out to it rather than growing a
        # second definition here.
        "hash_function": "sonar_source_hash",
        "scanner_field": "scannerGav",
        "scan_command": "tools/sonar/scan.sh",
        "ratchet_command": "tools/sonar/ratchet.sh",
        "report": "tools/sonar/sonar-report.json",
        "baseline": "tools/sonar/sonar-baseline.json",
    },
    "ui": {
        "comment": UI_BASELINE_COMMENT,
        "hash_function": "sonar_ui_source_hash",
        "scanner_field": "scannerImage",
        "scan_command": "tools/sonar/scan-ui.sh",
        "ratchet_command": "tools/sonar/ratchet-ui.sh",
        "report": "tools/sonar/sonar-ui-report.json",
        "baseline": "tools/sonar/sonar-ui-baseline.json",
    },
}


def _fail(message: str) -> None:
    sys.stderr.write(f"seed-baseline: {message}\n")
    raise SystemExit(2)


def _counts(report: dict[str, Any], scan_command: str) -> dict[str, int]:
    raw = report.get("counts")
    if not isinstance(raw, dict):
        _fail(f"the scan report has no counts object. Regenerate it with {scan_command}.")
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


def _actual_source_hash(repo_root: Path, hash_function: str) -> str:
    """Recompute the freshness token by asking the shell library for it.

    Shelling out rather than reimplementing the pipeline, for exactly the reason
    tools/sonar/ratchet.sh gives: two definitions of the token would agree until
    they did not, and the symptom would be a bootstrap refused as stale on a
    tree scanned seconds earlier.

    hash_function names the function to call - sonar_source_hash for Java,
    sonar_ui_source_hash for the front end. The two cover disjoint file sets,
    which is what makes seeding one project from the other's report fail here
    rather than succeed quietly with the wrong ceiling.
    """
    library = repo_root / "tools" / "sonar" / "source-hash.sh"
    if not library.exists():
        _fail(f"the source-hash library is missing at {library}.")
    try:
        result = subprocess.run(
            ["bash", "-c", f'set -euo pipefail; . "{library}"; {hash_function}'],
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
    # No defaults here: they are resolved from --flavour below, so that naming a
    # flavour is sufficient and naming neither still means the Java pair.
    parser.add_argument("--report")
    parser.add_argument("--baseline")
    parser.add_argument(
        "--force",
        action="store_true",
        help="overwrite an existing baseline - raises the ceiling, so review the diff",
    )
    # Defaults to java so that the command recorded in ADR-060 and in the
    # remedy text ratchet.py prints keeps working unchanged. The front end is
    # the addition, so the front end is the one that names itself.
    parser.add_argument(
        "--flavour",
        choices=sorted(FLAVOURS),
        default="java",
        help="which project's baseline this is - selects the prose, the source-hash "
        "function and the provenance field (default: java)",
    )
    args = parser.parse_args()

    flavour = FLAVOURS[args.flavour]
    scan_command = flavour["scan_command"]
    ratchet_command = flavour["ratchet_command"]

    repo_root = Path(
        subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    )
    os.chdir(repo_root)

    report_path = Path(args.report or flavour["report"])
    baseline_path = Path(args.baseline or flavour["baseline"])

    if not report_path.exists():
        _fail(
            f"no scan report at {report_path}. Start the local stack with\n"
            "  docker compose -f compose.sonar.yml up -d\n"
            f"then run {scan_command}."
        )

    if baseline_path.exists() and not args.force:
        _fail(
            f"a baseline already exists at {baseline_path}, and overwriting it raises the\n"
            "  ceiling rather than lowering it. If a scan found FEWER issues, you want\n"
            f"  {ratchet_command} --tighten instead, which lowers the ceiling and needs\n"
            "  no flag. If you really are admitting new issues, re-run with --force and say\n"
            "  in the commit message which rule fired and why living with it beats fixing it."
        )

    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        _fail(f"{report_path} is not valid JSON: {exc}")

    reported_hash = report.get("sourceHash")
    if not isinstance(reported_hash, str) or not reported_hash:
        _fail(f"{report_path} carries no sourceHash. Regenerate it with {scan_command}.")

    actual_hash = _actual_source_hash(repo_root, flavour["hash_function"])
    if actual_hash != reported_hash:
        _fail(
            "the scan report is stale - it was measured on a different tree, so its counts\n"
            f"  would seed a ceiling for code nobody has.\n"
            f"    report: {reported_hash}\n"
            f"    actual: {actual_hash}\n"
            f"  Re-run {scan_command}, then seed from the fresh report."
        )

    issues = report.get("issues")
    if not isinstance(issues, list):
        _fail(f"{report_path} has no issues array. Regenerate it with {scan_command}.")

    baseline = {
        "_comment": flavour["comment"],
        "counts": _counts(report, scan_command),
        # Provenance, so a reader of a raised ceiling can find the scan it came
        # from. Deliberately not the sourceHash: a baseline is not tied to one
        # tree the way a report is, and recording a hash here would read as a
        # freshness claim the baseline does not make and must not make - it is a
        # ceiling that outlives the code it was measured against.
        #
        # The scanner is recorded under the field the report used, because the
        # two projects are analysed by different things: a Maven plugin resolved
        # by GAV for Java, a digest-pinned container image for the front end.
        # One generic key would make the value's meaning depend on which file
        # you are reading, which is precisely the ambiguity provenance exists to
        # remove.
        "seededFrom": {
            "generatedAt": report.get("generatedAt"),
            "sonarQubeVersion": report.get("sonarQubeVersion"),
            flavour["scanner_field"]: report.get(flavour["scanner_field"]),
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
