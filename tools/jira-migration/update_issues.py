#!/usr/bin/env python3
"""Phase 4 of the Jira -> GitHub migration: the second pass over created issues.

Phase 3 created all 164 issues, but their bodies were rendered with ``xref=None``
because GitHub's issue numbers did not exist yet, so every cross-reference came
out as a bare ``EOP-N``.  This script re-renders every body from the *same local
ADF* in ``docs/jira-export/`` with the completed ``mapping.json``, so a reference
becomes ``EOP-123 (#246)`` -- the Jira key stays searchable and GitHub gets a
native reference that also produces a backlink on the target issue.

Re-rendering from ADF rather than regex-rewriting the finished Markdown is the
whole reason the converter is structural: it knows which text sits inside a code
span, so the 51 in-code ``EOP-N`` mentions (several of them example commit
messages like ``[EOP-166] chore: ...``) are never rewritten.  A regex pass over
finished Markdown would corrupt every one of them.

Four steps, deliberately in this order:

1. PATCH all 164 bodies (pass two).
2. POST the 96 comments, in Jira's creation order per issue.
3. Wire the 43 parent/child relationships via the native sub-issues API.
4. Close the 85 ``Done`` issues with ``state_reason=completed``.

Closing comes last on purpose.  Steps 2 and 3 are legal against a closed issue,
but the reverse ordering makes the run harder to reason about if it dies partway,
and the sub-issues API is the least-exercised endpoint here -- so it runs while
everything involved is still open and ordinary.

Every step records progress to a local JSON file after each individual write, so
a crash costs only the request in flight.  Re-running skips completed work.
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

from adf_to_markdown import Converter, discover_adr_files
from bodies import (
    ADR_DIR,
    EXPORT_DIR,
    build_comment_body,
    build_issue_body,
    load_export,
)

REPO = "maglez/eop-threat-medeling"
MAPPING = EXPORT_DIR / "mapping.json"

# Progress lives next to the scripts, not in docs/jira-export/, because it is
# run bookkeeping rather than exported data and must not reach the commit.
PROGRESS = Path(__file__).resolve().parent / ".phase4-progress.json"

# GitHub's *secondary* rate limit governs content-creating requests and is far
# tighter than the 5000/hour primary budget.  One request per ~1.1s stays under
# it for the ~390 writes this script makes.
THROTTLE_SECONDS = 1.1
RETRIES = 4

# Transient failures worth retrying; anything else is a bug and must stop the run.
TRANSIENT = ("rate limit", "abuse", "secondary", "502", "503", "504", "timeout")


def load_progress() -> dict[str, list]:
    """Return the record of completed writes, or an empty one on first run."""
    if PROGRESS.exists():
        return json.loads(PROGRESS.read_text(encoding="utf-8"))
    return {"bodies": [], "comments": [], "sub_issues": [], "closed": []}


def save_progress(progress: dict[str, list]) -> None:
    """Persist progress immediately so a crash costs only the write in flight."""
    PROGRESS.write_text(json.dumps(progress, indent=2) + "\n", encoding="utf-8")


def gh(args: list[str], payload: dict | None = None) -> dict:
    """Run a gh api call, passing any JSON payload on stdin, retrying transients.

    stdin rather than -f flags because bodies run to ~9 KB and carry newlines,
    quotes and backticks that have no business going through a shell argument.
    """
    text = json.dumps(payload) if payload is not None else None
    for attempt in range(1, RETRIES + 1):
        result = subprocess.run(
            ["gh", "api", *args],
            input=text,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            return json.loads(result.stdout) if result.stdout.strip() else {}
        stderr = result.stderr.lower()
        if attempt < RETRIES and any(token in stderr for token in TRANSIENT):
            wait = 20 * attempt
            print(f"    transient failure, retrying in {wait}s: {result.stderr.strip()[:200]}")
            time.sleep(wait)
            continue
        sys.exit(f"gh api failed: {' '.join(args)}\n{result.stderr.strip()}")
    raise AssertionError("unreachable")


def patch_bodies(export: dict, mapping: dict[str, dict], converter: Converter,
                 progress: dict[str, list]) -> None:
    """Rewrite every issue body with cross-references resolved to real numbers."""
    done = set(progress["bodies"])
    issues = sorted(export["issues"], key=lambda i: int(i["key"].split("-")[1]))
    todo = [i for i in issues if i["key"] not in done]
    print(f"step 1: {len(todo)} bodies to rewrite ({len(done)} already done)")
    for index, issue in enumerate(todo, start=1):
        key = issue["key"]
        number = mapping[key]["number"]
        body = build_issue_body(issue, export, converter)
        gh(["--method", "PATCH", f"repos/{REPO}/issues/{number}", "--input", "-"],
           {"body": body})
        progress["bodies"].append(key)
        save_progress(progress)
        print(f"  [{index}/{len(todo)}] {key} -> #{number}  ({len(body)} B)")
        time.sleep(THROTTLE_SECONDS)


def post_comments(export: dict, mapping: dict[str, dict], converter: Converter,
                  progress: dict[str, list]) -> None:
    """Post every Jira comment, preserving its original author and timestamps in text."""
    done = set(progress["comments"])
    pending: list[tuple[str, dict]] = []
    for key in sorted(export["comments"], key=lambda k: int(k.split("-")[1])):
        for comment in export["comments"][key]:
            if f"{key}:{comment['id']}" not in done:
                pending.append((key, comment))
    print(f"step 2: {len(pending)} comments to post ({len(done)} already done)")
    for index, (key, comment) in enumerate(pending, start=1):
        number = mapping[key]["number"]
        body = build_comment_body(comment, converter)
        gh(["--method", "POST", f"repos/{REPO}/issues/{number}/comments", "--input", "-"],
           {"body": body})
        progress["comments"].append(f"{key}:{comment['id']}")
        save_progress(progress)
        print(f"  [{index}/{len(pending)}] {key} -> #{number}  ({len(body)} B)")
        time.sleep(THROTTLE_SECONDS)


def wire_sub_issues(export: dict, mapping: dict[str, dict],
                    progress: dict[str, list]) -> None:
    """Attach each child to its epic via the native sub-issues API.

    The endpoint takes the parent's issue *number* in the path but the child's
    internal *id* in the payload, which is why Phase 3 recorded both.  Limits are
    100 sub-issues per parent and 8 levels of nesting; the largest epic here has
    29 children at one level, so both are comfortable.
    """
    done = set(progress["sub_issues"])
    pending: list[tuple[str, str]] = []
    for issue in sorted(export["issues"], key=lambda i: int(i["key"].split("-")[1])):
        parent = issue["fields"].get("parent")
        if parent and issue["key"] not in done:
            pending.append((parent["key"], issue["key"]))
    print(f"step 3: {len(pending)} sub-issue links to wire ({len(done)} already done)")
    for index, (parent_key, child_key) in enumerate(pending, start=1):
        parent_number = mapping[parent_key]["number"]
        child_id = mapping[child_key]["id"]
        gh(["--method", "POST", f"repos/{REPO}/issues/{parent_number}/sub_issues",
            "--input", "-"], {"sub_issue_id": child_id})
        progress["sub_issues"].append(child_key)
        save_progress(progress)
        print(f"  [{index}/{len(pending)}] {child_key} -> child of {parent_key} (#{parent_number})")
        time.sleep(THROTTLE_SECONDS)


def close_done(export: dict, mapping: dict[str, dict], progress: dict[str, list]) -> None:
    """Close every issue whose Jira status category is Done, as *completed*.

    state_reason matters: the default for a closed issue is ``completed`` only if
    stated, and a migration that closed 85 issues as ``not_planned`` would
    misrepresent every one of them.
    """
    done = set(progress["closed"])
    pending = [
        issue for issue in sorted(export["issues"], key=lambda i: int(i["key"].split("-")[1]))
        if issue["fields"]["status"]["statusCategory"]["key"] == "done"
        and issue["key"] not in done
    ]
    print(f"step 4: {len(pending)} issues to close ({len(done)} already done)")
    for index, issue in enumerate(pending, start=1):
        key = issue["key"]
        number = mapping[key]["number"]
        gh(["--method", "PATCH", f"repos/{REPO}/issues/{number}", "--input", "-"],
           {"state": "closed", "state_reason": "completed"})
        progress["closed"].append(key)
        save_progress(progress)
        print(f"  [{index}/{len(pending)}] {key} -> #{number} closed")
        time.sleep(THROTTLE_SECONDS)


def main() -> None:
    export = load_export()
    records = json.loads(MAPPING.read_text(encoding="utf-8"))
    mapping = {record["key"]: record for record in records}
    if len(mapping) != len(export["issues"]):
        sys.exit(f"mapping holds {len(mapping)} entries but export holds "
                 f"{len(export['issues'])} issues; run create_issues.py first")

    xref = {key: record["number"] for key, record in mapping.items()}
    converter = Converter(discover_adr_files(ADR_DIR), xref=xref)
    progress = load_progress()

    patch_bodies(export, mapping, converter, progress)
    post_comments(export, mapping, converter, progress)
    wire_sub_issues(export, mapping, progress)
    close_done(export, mapping, progress)

    print()
    print(f"bodies {len(progress['bodies'])}  comments {len(progress['comments'])}  "
          f"sub-issues {len(progress['sub_issues'])}  closed {len(progress['closed'])}")
    print(f"unresolved cross-references: {converter.stats.get('xref_unresolved', 0)} "
          "(expected 1 per EOP-000 mention -- a prose placeholder, not an issue)")


if __name__ == "__main__":
    main()
