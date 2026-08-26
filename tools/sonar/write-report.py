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

    counts = {
        "RELIABILITY": _int_measure(measures, "software_quality_reliability_issues"),
        "MAINTAINABILITY": _int_measure(measures, "software_quality_maintainability_issues"),
        "SECURITY": _int_measure(measures, "software_quality_security_issues"),
    }

    # A cheap internal consistency check, and the reason it is here rather than
    # in the ratchet: the three gated counts come from /api/measures/component
    # while the fingerprint list comes from /api/issues/search, and the two are
    # separate server-side code paths over the same data. If they ever disagree
    # the report is not describing one coherent analysis, and a baseline written
    # from it would gate on one number while naming findings from another. We
    # warn rather than fail, because the counts are the gated quantity and are
    # the more trustworthy of the two - but a silent disagreement is exactly the
    # kind of thing that gets discovered a year later.
    if sum(counts.values()) != len(issues):
        sys.stderr.write(
            "write-report: WARNING measures total {} but the issue inventory has {} entries. "
            "The gate will use the measures. Check for issues the search paged past.\n".format(
                sum(counts.values()), len(issues)
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
            "counts.* are the three gated numbers. scope.* is context for reading a regression",
            "and is never gated on. coverage is recorded because it is free to record and it",
            "explains the 68%-vs-95% discrepancy in ADR-060; JaCoCo owns the coverage gate and",
            "this file has no say in it.",
        ],
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sonarQubeVersion": _env("SONAR_SERVER_VERSION"),
        "scannerGav": _env("SONAR_SCANNER_GAV"),
        "sourceHash": _env("SONAR_SOURCE_HASH"),
        "sourceFileCount": int(_env("SONAR_FILE_COUNT")),
        "counts": counts,
        "scope": {
            "MAIN": {
                "total": _total(main_search),
                "byQuality": _facet(main_search, "impactSoftwareQualities"),
            },
            "TEST": {
                "total": _total(test_search),
                "byQuality": _facet(test_search, "impactSoftwareQualities"),
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
