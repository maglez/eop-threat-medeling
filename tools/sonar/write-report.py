#!/usr/bin/env python3
"""Turn the raw SonarQube Web API responses into tools/sonar/sonar-report.json.

Invoked only by tools/sonar/scan.sh, which passes everything through the
environment and captures stdout. See ADR-060.

Splitting this out of the shell script is not fastidiousness about language
choice: the input is four JSON documents and a line-oriented inventory, and the
output has to be byte-stable across two machines so that `git diff` on the
report shows the analysis changing and nothing else. `jq` could do it, but the
sort/indent/separator guarantees below are the whole point of the file and they
are clearer as code than as a jq program nobody will re-read.

Byte stability is worth spelling out, because it is the property that makes the
committed report reviewable at all:

  * `sort_keys=True` - so a Python dict iteration order can never reorder a
    field and produce a diff that looks like a change.
  * `indent=2` and an explicit `separators` - so no trailing whitespace creeps
    in on the platform whose default differs.
  * the issue list is sorted as a multiset, duplicates preserved. Two identical
    fingerprints are a real thing (the same rule firing twice on two lines whose
    content happens to be identical), and de-duplicating them would silently
    lower the count the ratchet compares.

Deliberately NOT recorded: `generatedAt` is written, but nothing reads it -
staleness is decided by `sourceHash` alone. A timestamp is for the human who
opens the file wondering how old it is; treating it as the freshness signal
would be strictly worse, because it goes stale on its own while the analysis is
still perfectly valid.
"""

from __future__ import annotations

import datetime
import json
import os
import sys


def _env(name: str) -> str:
    value = os.environ.get(name)
    if value is None or value == "":
        sys.stderr.write(f"write-report: ${name} is not set - run via tools/sonar/scan.sh\n")
        raise SystemExit(2)
    return value


def _measures(measures_json: str) -> dict[str, str]:
    """Flatten /api/measures/component into metric -> value.

    A metric with no value on this project is absent from the response rather
    than present as null, so callers must treat a missing key as "not measured"
    and never as zero. The three issue counts are mandatory below for exactly
    that reason.
    """
    document = json.loads(measures_json)
    flattened: dict[str, str] = {}
    for measure in document.get("component", {}).get("measures", []):
        flattened[measure["metric"]] = measure.get("value", "")
    return flattened


# The three software qualities the ratchet gates on. Kept in the same order as
# tools/sonar/ratchet.py and tools/sonar/seed-baseline.py so the three scripts
# print their tables identically.
GATED_QUALITIES = ("RELIABILITY", "MAINTAINABILITY", "SECURITY")


def _facet(search_json: str, facet_property: str) -> dict[str, int]:
    """Pull one facet out of an /api/issues/search response as val -> count.

    SonarQube returns every possible value of a facet including the zeroes, so
    SECURITY appears here as 0 rather than being absent. That is convenient: the
    report carries an explicit zero, and a later scan that finds the first
    security issue produces a 0 -> 1 diff instead of a key appearing out of
    nowhere.
    """
    document = json.loads(search_json)
    for facet in document.get("facets", []):
        if facet.get("property") == facet_property:
            return {value["val"]: value["count"] for value in facet.get("values", [])}
    return {}


def _gated_counts(by_quality: dict[str, int]) -> dict[str, int]:
    """The three gated numbers, read from the MAIN-scope facet.

    Refuses to write a report with a missing gated count, for the same reason
    _int_measure does: ratchet.py treats an absent key as a hard failure rather
    than as a zero, so a report that reaches it incomplete turns a facet rename
    or an empty search into a confusing failure two steps downstream instead of
    a clear one here.
    """
    counts: dict[str, int] = {}
    for quality in GATED_QUALITIES:
        if quality not in by_quality:
            sys.stderr.write(
                f"write-report: the MAIN scope facet carried no '{quality}' entry. SonarQube "
                "normally returns every facet value including the zeroes, so this means the "
                "facet was renamed by a server upgrade or the search returned no facets at "
                "all; refusing to write a report with a guessed count.\n"
            )
            raise SystemExit(2)
        counts[quality] = int(by_quality[quality])
    return counts


def _total(search_json: str) -> int:
    return int(json.loads(search_json)["paging"]["total"])


def _int_measure(measures: dict[str, str], metric: str) -> int:
    raw = measures.get(metric)
    if raw is None or raw == "":
        sys.stderr.write(
            f"write-report: SonarQube returned no value for '{metric}'. The analysis is "
            "incomplete or the metric was renamed by a server upgrade; refusing to write a "
            "report with a guessed count.\n"
        )
        raise SystemExit(2)
    return int(raw)


def _optional_float(measures: dict[str, str], metric: str) -> float | None:
    """Optional fractional measure. Returns None when the server has no value.

    Coverage legitimately has no value on a scan run with --skip-build against
    a tree with no JaCoCo XML, and coverage is explicitly not gated on, so its
    absence must not fail the scan the way a missing issue count does.
    """
    raw = measures.get(metric)
    if raw is None or raw == "":
        return None
    return float(raw)


def _optional_int(measures: dict[str, str], metric: str) -> int | None:
    """Optional whole-number measure. Returns None when the server has no value.

    Distinct from _optional_float rather than a convenience over it, because
    ncloc and tests are counts and JSON has no integer type to recover them
    later: float() would serialise 7338 as 7338.0, which reads as a measurement
    precise to a tenth of a line and invites a reader to wonder what the
    fraction means. Nothing is gated on either number, so this is legibility
    rather than correctness - but a context field nobody can read confidently
    is a context field nobody uses.
    """
    raw = measures.get(metric)
    if raw is None or raw == "":
        return None
    return int(float(raw))


def main() -> int:
    measures = _measures(_env("SONAR_MEASURES"))

    main_search = _env("SONAR_MAIN")
    test_search = _env("SONAR_TEST")

    with open(_env("SONAR_INVENTORY_FILE"), encoding="utf-8") as handle:
        issues = [line.strip() for line in handle if line.strip()]

    main_by_quality = _facet(main_search, "impactSoftwareQualities")
    test_by_quality = _facet(test_search, "impactSoftwareQualities")

    # The gated counts are the MAIN facet, not /api/measures/component. Since
    # 2026-09-02 the ratchet is production-scoped: test code is still analysed,
    # still hashed and still recorded under scope.TEST, but a finding there no
    # longer fails the build. ADR-060 as amended carries the reasoning, and note
    # what the reason is not - it is not coverage. SonarQube already classifies
    # src/test/java under sonar.tests and excludes it from the coverage
    # denominator, which is why coverage reads 95.1% with test code fully in
    # scope. What needed narrowing was the gate, not the analysis.
    counts = _gated_counts(main_by_quality)

    # The whole-tree figures the project overview page shows, kept as context so
    # that the narrowing is auditable rather than a quiet deletion: a reader who
    # sees MAINTAINABILITY 31 here and 232 in the browser can reconcile the two
    # from this file alone, without being told which number to trust.
    whole_tree = {
        "RELIABILITY": _int_measure(measures, "software_quality_reliability_issues"),
        "MAINTAINABILITY": _int_measure(measures, "software_quality_maintainability_issues"),
        "SECURITY": _int_measure(measures, "software_quality_security_issues"),
    }

    # Consistency check one. The gated counts and the fingerprint list now come
    # from the same endpoint - /api/issues/search with scopes=MAIN - so this is a
    # facet-versus-paginated-list check, not the cross-endpoint check it was
    # before the narrowing. It still catches the failure that matters, a baseline
    # that gates on one number while naming findings from another, but it no
    # longer independently corroborates the server. Check two restores that.
    if sum(counts.values()) != len(issues):
        sys.stderr.write(
            "write-report: WARNING the MAIN facet totals {} but the production issue "
            "inventory has {} entries. The gate will use the facet. Check for issues the "
            "search paged past.\n".format(sum(counts.values()), len(issues))
        )

    # Consistency check two, and the reason the TEST facet is harvested at all
    # now that it is not gated on: MAIN plus TEST must reconcile with the
    # whole-tree measures, which come from a separate server-side code path. That
    # is what keeps the narrowing honest. If the two sides stop adding up, either
    # a file is misclassified by scope or the gate is ignoring findings that
    # nothing in this report accounts for - and the second of those is precisely
    # the failure a production-scoped ratchet has to be able to rule out.
    for quality in GATED_QUALITIES:
        split = counts[quality] + test_by_quality.get(quality, 0)
        if split != whole_tree[quality]:
            sys.stderr.write(
                "write-report: WARNING {} splits as MAIN {} + TEST {} = {}, but the "
                "whole-tree measure is {}. The scope split does not account for every "
                "issue.\n".format(
                    quality,
                    counts[quality],
                    test_by_quality.get(quality, 0),
                    split,
                    whole_tree[quality],
                )
            )

    report = {
        "_comment": [
            "GENERATED FILE - written by tools/sonar/scan.sh, do not hand-edit.",
            "",
            "This is the evidence half of the SonarQube ratchet (ADR-060). It records what a",
            "local scan of this exact source tree found. tools/sonar/ratchet.sh compares it",
            "against tools/sonar/sonar-baseline.json and runs in CI with no server.",
            "",
            "sourceHash is the freshness token. CI recomputes it over pom.xml plus every .java",
            "file under src/main/java and src/test/java (see tools/sonar/source-hash.sh) and",
            "fails if it differs from the value here. That is what stops this file from being",
            "a permanently green rubber stamp: without it, a developer who never rescans keeps",
            "a passing gate no matter what they add.",
            "",
            "So: if CI tells you this report is stale, the fix is to rescan, not to edit the",
            "hash. Start the stack and run tools/sonar/scan.sh.",
            "",
            "counts.* are the three gated numbers, and since 2026-09-02 they cover production",
            "code only - the MAIN scope. scope.MAIN repeats them, scope.TEST carries the test",
            "code the gate declines to count, and scope.ALL carries the whole-tree figures the",
            "project overview page shows, so all three reconcile from this file alone. Test",
            "code is still analysed and still hashed; only the gate narrowed. See ADR-060 as",
            "amended.",
            "",
            "coverage is recorded because it is free to record and it explains the 68%-vs-95%",
            "discrepancy in ADR-060; JaCoCo owns the coverage gate and this file has no say in",
            "it. Note that coverage is unaffected by the scope narrowing above: SonarQube",
            "already excluded src/test/java from the coverage denominator via sonar.tests.",
        ],
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sonarQubeVersion": _env("SONAR_SERVER_VERSION"),
        "scannerGav": _env("SONAR_SCANNER_GAV"),
        "sourceHash": _env("SONAR_SOURCE_HASH"),
        "sourceFileCount": int(_env("SONAR_FILE_COUNT")),
        "counts": counts,
        "scope": {
            # No `total` for ALL. MAIN and TEST take theirs from paging.total,
            # which is an issue count, whereas these are per-quality measures
            # that would double-count an issue carrying two impacts. A summed
            # field here would look like the same quantity and quietly not be.
            "ALL": {
                "byQuality": whole_tree,
            },
            "MAIN": {
                "total": _total(main_search),
                "byQuality": main_by_quality,
            },
            "TEST": {
                "total": _total(test_search),
                "byQuality": test_by_quality,
            },
        },
        "coverage": _optional_float(measures, "coverage"),
        "ncloc": _optional_int(measures, "ncloc"),
        "tests": _optional_int(measures, "tests"),
        # Sorted, duplicates kept. See the module docstring.
        "issues": sorted(issues),
    }

    json.dump(report, sys.stdout, indent=2, sort_keys=True, separators=(",", ": "))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
