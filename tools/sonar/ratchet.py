#!/usr/bin/env python3
"""Compare a committed SonarQube report against the committed ratchet ceiling.

Talks to nothing. Reads two JSON files and the working tree, and decides whether
this commit is allowed. This is the half of the gate that runs in CI, which is
why it must have no network dependency at all - the requirement that produced
this design was, verbatim, "compare in CI without a live server".

Invoked through tools/sonar/ratchet.sh or tools/sonar/ratchet-ui.sh, never
directly, because the shell wrapper owns the one thing this file cannot compute
for itself: the source hash, whose definition lives in tools/sonar/source-hash.sh
so that the scanning side and the checking side cannot drift apart.

TWO PROJECTS, ONE RATCHET (ADR-063). There are two SonarQube projects - Java
(eop-threat-modeling) and the front end (eop-threat-modeling-ui) - and
--flavour selects which one is being checked. It changes the log prefix, the
default report and baseline paths, the commands named in every remedy message,
and two paragraphs of printed prose describing what the gate covers. Nothing in
the comparison logic branches on it: the multiset difference, the freshness
short-circuit and the count table are identical, because the two projects differ
in what they measure and not in how a ceiling works.

Sharing one implementation is deliberate, for the reason tools/sonar/
source-hash.sh gives about itself. The delicate logic here is the refusal to
compare a stale report and the refusal to infer a missing count as zero, and two
copies of a refusal is one copy that can rot unnoticed while the other is
maintained. --flavour defaults to java so that ratchet.sh, the command in
ADR-060 and every remedy message printed by the other scripts keep working
unchanged; the front end is the addition, so the front end names itself.

SCOPE, since 2026-09-02 (EOP-000, ADR-060 as amended): for the Java project the
three integers this file compares cover PRODUCTION CODE ONLY - SonarQube's MAIN
scope, which is src/main/java. They are not the numbers the SonarQube project
overview page shows, and a reader who takes "MAINTAINABILITY 31" for the whole
project will be wrong by an order of magnitude. The whole-tree figure and the
test-code side are both recorded in the report under scope.ALL and scope.TEST,
so the narrowing is auditable from the file rather than asserted here. The
front-end project narrows the same way, for the same reason, over ui/src.

Test code is still analysed, and still hashed. Only the gate narrowed. The
reason was not coverage - SonarQube already classifies src/test/java under
sonar.tests, so it was never in the coverage denominator and coverage read 95.1%
with test code fully in scope. The reason was that 211 of 243 findings sat in
test code against a ceiling with no headroom, so a routine new test file
reddened the gate for reasons that said nothing about the product.

Exit codes - the contract shared by every gate under tools/:
  0  clean. Counts are at or below the ceiling and the report describes this tree.
  1  gating finding. A count rose, or the report is stale, or the files disagree
     with each other in a way that means the gate cannot be trusted to be honest.
  2  could not run. A file is missing or unparseable.

The 1-versus-2 line matters more here than in most gates, because "stale report"
is genuinely a gating finding rather than an infrastructure failure: the
developer changed code and did not rescan, so we do not know whether they added
an issue, and not knowing must block. Exit 2 is reserved for the gate itself
being broken.

WHAT THIS GATE DOES NOT PROVE, stated here because a green tick invites the
opposite reading:

  * Not that the code is good. It proves three integers did not increase.
  * Not that the report was produced honestly. A developer can edit
    sonar-report.json by hand and CI cannot tell, exactly as they can edit any
    committed baseline in this repository. The freshness hash closes the
    accident (forgot to rescan), not the intent (chose to lie). Review closes
    the intent, as it does for tools/supply-chain/accepted-cves.json.
  * Not that SonarQube's opinion is stable. A server upgrade retunes rules, so
    the same source can score differently. That is why the report records
    sonarQubeVersion and why compose.sonar.yml pins the image by digest.
  * Not that test code is clean. Test findings are measured and recorded in the
    report, and they are deliberately not gated, so this gate going green says
    nothing at all about the 36,965 lines under src/test/java - nor, on the
    front-end flavour, about the test files under ui/src.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

QUALITIES = ("RELIABILITY", "MAINTAINABILITY", "SECURITY")

# ---------------------------------------------------------------------------
# The two projects
# ---------------------------------------------------------------------------
# Everything that differs between the Java gate and the front-end gate is a row
# in this table, so adding a third project would be a row rather than a new
# branch in eight print statements. The prose entries are lists of already
# indented lines because they are printed verbatim; keeping them here rather
# than as CLI strings means the shell wrappers pass one flag instead of quoting
# multi-line English through argv.
FLAVOURS: dict[str, dict[str, Any]] = {
    "java": {
        "label": "sonar-ratchet",
        "report": "tools/sonar/sonar-report.json",
        "baseline": "tools/sonar/sonar-baseline.json",
        "scan_command": "tools/sonar/scan.sh",
        "seed_command": "python3 tools/sonar/seed-baseline.py",
        "gated_scope": [
            "  Gated scope: production code only (src/main/java). Test-code findings are",
            "  measured and recorded in the report under scope.TEST, and not gated.",
        ],
        "hash_scope": [
            "  The hash covers pom.xml and every .java file under src/main/java and",
            "  src/test/java. A docs-only, workflow-only or ui/-only change leaves it",
            "  untouched and needs no rescan - see tools/sonar/source-hash.sh.",
        ],
    },
    "ui": {
        "label": "sonar-ratchet-ui",
        "report": "tools/sonar/sonar-ui-report.json",
        "baseline": "tools/sonar/sonar-ui-baseline.json",
        "scan_command": "tools/sonar/scan-ui.sh",
        "seed_command": "python3 tools/sonar/seed-baseline.py --flavour ui",
        "gated_scope": [
            "  Gated scope: front-end production code only (ui/src, excluding *.test.ts",
            "  and *.test.tsx). Test-file findings are measured and recorded in the report",
            "  under scope.TEST, and not gated.",
        ],
        "hash_scope": [
            "  The hash covers ui/package.json, ui/tsconfig.json, ui/vite.config.ts and",
            "  every .ts/.tsx file under ui/src. A change to Java, docs or workflows leaves",
            "  it untouched and needs no rescan - see tools/sonar/source-hash.sh.",
        ],
    },
}

# The log prefix every message carries. Set once from --flavour in main(), before
# anything can print. A module-level name rather than a parameter threaded through
# _load, _counts and _fingerprints: those three exist to refuse bad input, and
# widening all of their signatures to carry a string used only for a prefix would
# obscure what they are for. There is exactly one writer and it runs first.
_LABEL = FLAVOURS["java"]["label"]


def _load(path: Path, what: str, remedy: str) -> dict[str, Any]:
    if not path.exists():
        _fail_hard(
            f"{what} not found at {path}.\n"
            "  Both this file and its neighbour are committed, so on a fresh checkout they\n"
            "  are present - if one is missing, something removed it.\n"
            f"  {remedy}"
        )
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except json.JSONDecodeError as exc:
        _fail_hard(f"{what} at {path} is not valid JSON: {exc}")
    raise AssertionError("unreachable")


def _fail_hard(message: str) -> None:
    """Exit 2: the gate could not form an opinion."""
    print(f"{_LABEL}: cannot run - {message}", file=sys.stderr)
    raise SystemExit(2)


def _counts(document: dict[str, Any], what: str) -> dict[str, int]:
    """Read the three gated integers, refusing anything ambiguous.

    Every quality must be present and must be an int. A missing key is not
    treated as zero: zero is a legitimate ceiling (SECURITY is 0 on this
    project today) and inferring it from silence would mean a truncated file
    reads as the strictest possible baseline, failing every build with a
    confusing message instead of the accurate one.
    """
    raw = document.get("counts")
    if not isinstance(raw, dict):
        _fail_hard(f"{what} has no 'counts' object")
    missing = [q for q in QUALITIES if q not in raw]
    if missing:
        _fail_hard(f"{what} 'counts' is missing {', '.join(missing)}")
    result = {}
    for quality in QUALITIES:
        value = raw[quality]
        if not isinstance(value, int) or isinstance(value, bool):
            _fail_hard(f"{what} counts.{quality} is {value!r}, expected an integer")
        if value < 0:
            _fail_hard(f"{what} counts.{quality} is negative")
        result[quality] = value
    return result


def _fingerprints(document: dict[str, Any], what: str) -> list[str]:
    raw = document.get("issues")
    if not isinstance(raw, list):
        _fail_hard(f"{what} has no 'issues' array")
    for entry in raw:
        if not isinstance(entry, str):
            _fail_hard(f"{what} 'issues' contains a non-string entry: {entry!r}")
    return list(raw)


def _multiset_added(baseline: list[str], report: list[str]) -> list[str]:
    """Fingerprints present in the report more often than in the baseline.

    A multiset difference rather than a set difference, because a duplicate
    fingerprint is a real occurrence: the same rule can fire on two lines whose
    content is byte-identical, and set semantics would report the second one as
    already known. That would let a genuine new finding hide behind an existing
    one, which is the precise failure a fingerprint list exists to prevent.
    """
    remaining: dict[str, int] = {}
    for entry in baseline:
        remaining[entry] = remaining.get(entry, 0) + 1
    added = []
    for entry in report:
        if remaining.get(entry, 0) > 0:
            remaining[entry] -= 1
        else:
            added.append(entry)
    return added


def _describe(fingerprint: str) -> str:
    """Render a fingerprint for a human. Format: QUALITIES|rule|path|hash."""
    parts = fingerprint.split("|")
    if len(parts) != 4:
        return f"    {fingerprint}"
    qualities, rule, path, _line_hash = parts
    return f"    [{qualities}] {rule}\n      {path}"


def main() -> int:
    global _LABEL

    parser = argparse.ArgumentParser(add_help=True, description=__doc__)
    # Defaults to java so ratchet.sh, ADR-060's recorded command and every remedy
    # message printed by the other scripts keep working with no edit. --report and
    # --baseline default per flavour rather than to the Java paths, so that
    # "--flavour ui" on its own cannot check the front-end ceiling against the
    # Java report. Cross-wiring them by hand still fails, because the two source
    # hashes cover disjoint file sets and the freshness check runs first.
    parser.add_argument("--flavour", choices=sorted(FLAVOURS), default="java")
    parser.add_argument("--report", default=None)
    parser.add_argument("--baseline", default=None)
    parser.add_argument(
        "--actual-hash",
        required=True,
        help="source hash of the working tree, computed by tools/sonar/source-hash.sh",
    )
    parser.add_argument(
        "--actual-file-count",
        required=True,
        type=int,
        help="number of files in the hash set, for a more legible mismatch message",
    )
    parser.add_argument(
        "--tighten",
        action="store_true",
        help="rewrite the baseline when the report is cleaner (local use only; CI never passes this)",
    )
    args = parser.parse_args()

    flavour = FLAVOURS[args.flavour]
    _LABEL = flavour["label"]
    scan_command = flavour["scan_command"]
    seed_command = flavour["seed_command"]

    report_path = Path(args.report or flavour["report"])
    baseline_path = Path(args.baseline or flavour["baseline"])
    report = _load(
        report_path,
        "the scan report",
        f"Regenerate it with {scan_command}, which needs the local SonarQube stack up.",
    )
    baseline = _load(
        baseline_path,
        "the ratchet baseline",
        # Deliberately not "run scan.sh": the scan script writes the report, never
        # the baseline. Bootstrapping the ceiling from whatever the current scan
        # happens to say is a reviewed act - a script that silently recreated a
        # deleted baseline would turn "someone removed the gate" into "the gate
        # agrees with today's code", which is the one outcome a ratchet exists
        # to prevent. So the recipe is explicit and leaves a diff to review.
        "To bootstrap a ceiling deliberately, derive it from a fresh report:\n"
        f"    {scan_command} && {seed_command}\n"
        "  then read the resulting diff before committing it.",
    )

    # -----------------------------------------------------------------------
    # Freshness first, before any comparison
    # -----------------------------------------------------------------------
    # Checked before the counts on purpose. A stale report's counts are not
    # wrong so much as meaningless - they describe a different tree - and
    # reporting "maintainability is fine" from a stale file would be worse than
    # saying nothing. So staleness short-circuits.
    recorded_hash = report.get("sourceHash")
    if not isinstance(recorded_hash, str) or not recorded_hash:
        _fail_hard(f"the scan report has no 'sourceHash' - it was not written by {scan_command}")

    if recorded_hash != args.actual_hash:
        recorded_count = report.get("sourceFileCount")
        print(f"{_LABEL}: FAIL - the committed scan report is stale.")
        print()
        print("  The report describes a different source tree than the one in this commit,")
        print("  so it cannot tell us whether this change introduced a SonarQube issue.")
        print()
        print(f"    report sourceHash : {recorded_hash}")
        print(f"    this tree         : {args.actual_hash}")
        if isinstance(recorded_count, int) and recorded_count != args.actual_file_count:
            print(
                f"    file count        : report {recorded_count}, this tree {args.actual_file_count}"
                "  (files were added or removed)"
            )
        else:
            print(
                f"    file count        : {args.actual_file_count} on both sides"
                "  (same files, contents differ)"
            )
        print()
        print("  Fix it by rescanning - do not edit the hash:")
        print("    colima start                                  # if the VM is not running")
        print("    docker compose -f compose.sonar.yml up -d     # wait for (healthy)")
        print(f"    {scan_command}")
        print(f"    git add {report_path} {baseline_path}")
        print()
        for line in flavour["hash_scope"]:
            print(line)
        return 1

    # -----------------------------------------------------------------------
    # The ratchet proper
    # -----------------------------------------------------------------------
    report_counts = _counts(report, "the scan report")
    baseline_counts = _counts(baseline, "the ratchet baseline")

    regressions = [(q, baseline_counts[q], report_counts[q]) for q in QUALITIES if report_counts[q] > baseline_counts[q]]
    improvements = [(q, baseline_counts[q], report_counts[q]) for q in QUALITIES if report_counts[q] < baseline_counts[q]]

    print(f"{_LABEL}: SonarQube {report.get('sonarQubeVersion', '(version not recorded)')}"
          f", report generated {report.get('generatedAt', '(no timestamp)')}")
    print(f"{_LABEL}: report is fresh for this tree ({args.actual_file_count} files)")
    print()
    for line in flavour["gated_scope"]:
        print(line)
    print()
    print("  quality            ceiling   found")
    for quality in QUALITIES:
        marker = "  <-- REGRESSION" if report_counts[quality] > baseline_counts[quality] else (
            "  <-- improved" if report_counts[quality] < baseline_counts[quality] else ""
        )
        print(f"  {quality:<16} {baseline_counts[quality]:>8} {report_counts[quality]:>7}{marker}")
    print()

    if regressions:
        print(f"{_LABEL}: FAIL - the SonarQube issue count increased.")
        print()
        for quality, ceiling, found in regressions:
            print(f"  {quality}: {ceiling} -> {found}  (+{found - ceiling})")
        print()

        added = _multiset_added(_fingerprints(baseline, "the ratchet baseline"),
                               _fingerprints(report, "the scan report"))
        if added:
            print(f"  Findings present now and not in the baseline ({len(added)}):")
            print()
            for fingerprint in added:
                print(_describe(fingerprint))
            print()
            # The list length and the count delta can disagree in BOTH directions,
            # and each direction has a different cause, so we must branch rather
            # than narrate one of them and hope. Getting this wrong sends the
            # reader hunting for something that is not there.
            delta = sum(found - ceiling for _q, ceiling, found in regressions)
            if len(added) > delta:
                # More fingerprints than new issues: editing the content of an
                # already-flagged line changes its hash, so a pure edit shows up
                # as one added and one removed with no change in count.
                print("  Note: the added-findings list is longer than the count increase. Editing")
                print("  the content of an already-flagged line changes its fingerprint without")
                print("  adding an issue, so some entries above are moved rather than new.")
                print()
            elif len(added) < delta:
                # Fewer fingerprints than new issues: one issue can carry several
                # software qualities, and it counts once against each. A single
                # finding tagged [MAINTAINABILITY,RELIABILITY] raises two counts
                # by one apiece while adding one line above.
                print("  Note: the added-findings list is shorter than the count increase. One")
                print("  finding counts once against every software quality it carries, so a")
                print("  single entry above tagged with two qualities raises two counts.")
                print()
        else:
            print("  No new fingerprints, which means an existing finding gained a software")
            print("  quality rather than a new issue appearing - a rule was retuned, or the")
            print("  server version moved. Check sonarQubeVersion in the report.")
            print()

        print("  Fix the findings, then rescan and commit the report. If a finding is a")
        print("  deliberate, justified exception, raise the ceiling in the baseline in the")
        print("  same commit with the reason in the review - that is a decision, not a")
        print("  formality, and ADR-060 says it must be argued rather than nudged.")
        return 1

    if improvements:
        print(f"{_LABEL}: PASS - and this commit is cleaner than the ceiling.")
        for quality, ceiling, found in improvements:
            print(f"  {quality}: {ceiling} -> {found}  ({ceiling - found} fewer)")
        print()
        if args.tighten:
            # Local only. CI never passes --tighten, so CI never writes to the
            # repository. A PR-triggered job pushing a commit to a contributor's
            # branch needs write permission on every PR including one from a
            # fork, and it rewrites the thing the author is being reviewed on.
            # The developer commits the tightened baseline themselves, which
            # also puts it in the diff where a reviewer sees it.
            baseline["counts"] = report_counts
            baseline["issues"] = sorted(_fingerprints(report, "the scan report"))
            baseline["tightenedFrom"] = {
                "sonarQubeVersion": report.get("sonarQubeVersion"),
                "generatedAt": report.get("generatedAt"),
            }
            with baseline_path.open("w", encoding="utf-8") as handle:
                json.dump(baseline, handle, indent=2, sort_keys=True, separators=(",", ": "))
                handle.write("\n")
            print(f"  Tightened {baseline_path} to the lower counts. Commit it with your change,")
            print("  so the ratchet keeps the ground you just gained.")
        else:
            print("  The baseline was NOT tightened, because this run is read-only (CI never")
            print(f"  writes to the repository). Run {scan_command} locally to lower it.")
        return 0

    # The fingerprint lists can differ while the counts match - a finding moved
    # from one file to another, or a flagged line was edited. That is not a
    # regression under a count-based ratchet, and we say so rather than staying
    # silent, because the alternative is a developer discovering later that the
    # baseline's fingerprint list has quietly gone stale.
    if args.tighten:
        report_fingerprints = sorted(_fingerprints(report, "the scan report"))
        if report_fingerprints != sorted(_fingerprints(baseline, "the ratchet baseline")):
            baseline["issues"] = report_fingerprints
            with baseline_path.open("w", encoding="utf-8") as handle:
                json.dump(baseline, handle, indent=2, sort_keys=True, separators=(",", ": "))
                handle.write("\n")
            print(f"{_LABEL}: counts unchanged, but the findings moved. Refreshed the")
            print(f"  fingerprint list in {baseline_path} so a future regression can still name")
            print("  what is new. Commit it with your change.")
            print()

    print(f"{_LABEL}: PASS - counts are at the ceiling, nothing new.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
