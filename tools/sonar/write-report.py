#!/usr/bin/env python3
"""Turn the raw SonarQube Web API responses into a committed sonar report.

Invoked only by tools/sonar/scan.sh and tools/sonar/scan-ui.sh, which pass
everything through the environment and capture stdout. See ADR-060 and ADR-063.

Two flavours, selected by $SONAR_REPORT_FLAVOUR and differing in exactly two
respects - the prose in `_comment`, and which field records the scanner that
produced the analysis (`scannerGav` for the Maven plugin, `scannerImage` for the
pinned container). Everything else is shared, and that sharing is the point:
this file, ratchet.py and seed-baseline.py each hold one definition of an
invariant that now governs two projects, because two near-identical copies of a
gate is the failure mode tools/sonar/source-hash.sh already warns about. The
default is `java`, and the java branch is byte-identical to what this script
emitted before the front end existed, so tools/sonar/sonar-report.json did not
need regenerating when ADR-063 landed.

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


def _flavour() -> str:
    """Which project this report is for: 'java' (default) or 'ui'.

    Defaulting rather than requiring the variable is deliberate. The java branch
    has to keep emitting exactly what it emitted before ADR-063, and a default
    means tools/sonar/scan.sh needed no edit at all to keep doing that - so the
    Java path cannot have been broken by this change, because it was not touched.
    """
    value = os.environ.get("SONAR_REPORT_FLAVOUR", "java")
    if value not in ("java", "ui"):
        sys.stderr.write(
            f"write-report: $SONAR_REPORT_FLAVOUR is '{value}', expected 'java' or 'ui'. "
            "Run via tools/sonar/scan.sh or tools/sonar/scan-ui.sh.\n"
        )
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


JAVA_COMMENT = [
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
]

# Deliberately NOT cross-referenced from JAVA_COMMENT above, though a pointer to
# the front-end report would be useful there. That list is reproduced verbatim in
# the committed tools/sonar/sonar-report.json, and the two ways of adding a line
# to it are both worse than leaving it alone: hand-editing a file whose first
# line says do not hand-edit, or a full ./mvnw verify plus rescan of a project
# ADR-063 puts out of scope. Keeping it byte-identical is what lets ADR-063 claim
# the Java path was not touched rather than merely re-tested. The cross-reference
# lives in tools/sonar/source-hash.sh and in ADR-063 instead.

UI_COMMENT = [
    "GENERATED FILE - written by tools/sonar/scan-ui.sh, do not hand-edit.",
    "",
    "This is the evidence half of the front-end SonarQube ratchet (ADR-063). It records",
    "what a local scan of the TypeScript under ui/src found. tools/sonar/ratchet-ui.sh",
    "compares it against tools/sonar/sonar-ui-baseline.json and runs in CI with no",
    "server, in the sonar-ratchet-ui job.",
    "",
    "It is a SEPARATE SonarQube project from the Java one, key eop-threat-modeling-ui,",
    "and that separation is the whole decision in ADR-063. One polyglot project would",
    "add TypeScript findings to the Java ceiling seeded on 2026-09-02, so a front-end",
    "regression could be paid for out of Java headroom and vice versa - two unrelated",
    "populations behind one number. Two projects, two ceilings, two ratchets.",
    "",
    "sourceHash is the freshness token. CI recomputes it over ui/package.json,",
    "ui/tsconfig.json, ui/vite.config.ts and every .ts/.tsx file under ui/src (see",
    "sonar_ui_source_hash in tools/sonar/source-hash.sh) and fails if it differs from",
    "the value here. ui/package-lock.json is deliberately NOT in that set: it changes",
    "on every npm audit fix and cannot move these counts, because the TypeScript sensor",
    "reads tsconfig and source rather than the lockfile.",
    "",
    "So: if CI tells you this report is stale, the fix is to rescan, not to edit the",
    "hash. Start the stack and run tools/sonar/scan-ui.sh.",
    "",
    "counts.* are the three gated numbers over MAIN scope - the non-test TypeScript.",
    "scope.TEST carries the findings in ui/src/**/*.test.ts(x), measured and recorded",
    "but not gated, exactly as on the Java side. scope.ALL is the whole-tree figure the",
    "project overview page shows, so all three reconcile from this file alone.",
    "",
    "coverage comes from ui/coverage/lcov.info, produced by `npm run coverage` in ui/",
    "(Vitest v8 provider). It is recorded, not gated - there is no front-end equivalent",
    "of JaCoCo's check goal, and ADR-063 declines to add a second coverage limit on top",
    "of the issue ratchet. One number worth knowing before reading it: src/main.tsx is",
    "the React mount point, has no test, and is deliberately left in scope reporting",
    "0% rather than excluded, so this figure is a little lower than the component tests",
    "alone would suggest. An exclusion would have been the more flattering choice and",
    "the less honest one.",
    "",
    "`tests` is normally absent here. Vitest emits an LCOV coverage report but this",
    "repository feeds SonarQube no test-execution report, so the server has no value",
    "for that metric and the field reads null. That is not a broken scan.",
]


def main() -> int:
    flavour = _flavour()
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
    #
    # The comparison is against quality-impacts rather than inventory entries,
    # because one issue carries one fingerprint but counts once per software
    # quality it is tagged with. So a report with 26 findings, 9 of them tagged
    # twice, has a facet summing to 35 and is perfectly consistent - and the
    # earlier entry-count comparison flagged exactly that as though the search
    # had paged past nine issues. It went unnoticed because no Java finding
    # currently carries two qualities, which makes the two totals coincide on
    # that project by luck rather than by construction; the front end has nine
    # such findings and made the flaw visible on its first scan. ratchet.py has
    # always modelled this correctly - see its "one issue counts once per
    # software quality it carries" branch - so the check, not the gate, was the
    # thing out of step.
    #
    # Both halves are worth asserting, and they fail for different reasons. A
    # mismatch on impacts means the facet and the list disagree about how many
    # findings there are. A mismatch on entries against paging.total means the
    # pagination loop stopped early, which is the failure the old wording named
    # but could not actually detect.
    impacts = sum(len(entry.split("|")[0].split(",")) for entry in issues if entry.split("|")[0])
    if sum(counts.values()) != impacts:
        sys.stderr.write(
            "write-report: WARNING the MAIN facet totals {} but the production issue "
            "inventory accounts for {} quality-impacts across {} findings. The gate will "
            "use the facet.\n".format(sum(counts.values()), impacts, len(issues))
        )
    if _total(main_search) != len(issues):
        sys.stderr.write(
            "write-report: WARNING the MAIN search reports {} findings but the inventory "
            "has {} entries. The pagination loop stopped early.\n".format(
                _total(main_search), len(issues)
            )
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
        "_comment": JAVA_COMMENT if flavour == "java" else UI_COMMENT,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sonarQubeVersion": _env("SONAR_SERVER_VERSION"),
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

    # Which scanner produced this analysis. Two field names rather than one
    # generic "scanner", because the two values are not the same kind of thing
    # and a reader must not have to guess which: a Maven GAV names a plugin
    # resolved from a repository, a container image digest names a filesystem.
    # Recording both under one key would make the field's meaning depend on the
    # flavour, and the ratchet reads neither, so the only consumer is a human
    # reconstructing an analysis - exactly the reader an ambiguous name fails.
    if flavour == "java":
        report["scannerGav"] = _env("SONAR_SCANNER_GAV")
    else:
        report["scannerImage"] = _env("SONAR_SCANNER_IMAGE")

    json.dump(report, sys.stdout, indent=2, sort_keys=True, separators=(",", ": "))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
