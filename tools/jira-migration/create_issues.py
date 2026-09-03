#!/usr/bin/env python3
"""Phase 3 of the Jira -> GitHub migration: create one GitHub issue per Jira issue.

Creates all 164 issues in ascending key order, recording the key -> issue number mapping
to ``docs/jira-export/mapping.json`` after every single create.

Three properties matter here.

**Resumable.** The mapping file is re-read on startup and any key already present is
skipped, so a crash, a rate-limit wall or a Ctrl-C costs only the issue in flight. The
file is rewritten in full after each create (164 entries is nothing) so it is never left
half-written.

**Ascending key order.** GitHub allocates issue numbers sequentially from the same
counter it uses for pull requests, so creating in key order makes the resulting numbers
monotonic in the Jira key. That is a convenience for humans reading the backlog, not
something anything depends on -- the mapping records the number GitHub actually
returned, so a pull request opened midway through simply shifts later numbers.

**Bodies are pass one.** Cross-references are rendered as bare ``EOP-N`` text at this
stage, because the GitHub numbers they need to point at do not exist yet. Phase 4 re-runs
the same converter over the same local ADF with the completed mapping and patches every
body. Nothing here needs to be undone for that to work.

Deliberately *not* done here: closing the 85 ``Done`` issues, and posting comments. Both
belong to Phase 4, which already has to patch every body -- state and body travel in one
request rather than two.
"""

from __future__ import annotations

import json
import subprocess
import sys
import time

from adf_to_markdown import Converter, discover_adr_files
from bodies import ADR_DIR, EXPORT_DIR, build_issue_body, issue_title, load_export

REPO = "maglez/eop-threat-modeling"
MAPPING = EXPORT_DIR / "mapping.json"

# GitHub's secondary rate limit is the binding constraint on content-creating requests,
# not the 5000/hour primary one. Roughly one write per second stays clear of it.
THROTTLE_SECONDS = 1.1
RETRIES = 4

TYPE_LABEL = {"Epic": "epic", "Story": "story", "Task": "task"}
IN_PROGRESS_LABEL = "status:in-progress"
IMPORT_LABEL = "jira-import"


def key_number(key: str) -> int:
    """Return the numeric part of a Jira key, for ordering."""
    return int(key.split("-")[1])


def load_mapping() -> list[dict]:
    """Return the mapping recorded so far, or an empty list on a first run."""
    if not MAPPING.exists():
        return []
    return json.loads(MAPPING.read_text())


def save_mapping(records: list[dict]) -> None:
    """Rewrite the mapping file, sorted by Jira key."""
    ordered = sorted(records, key=lambda r: key_number(r["key"]))
    MAPPING.write_text(json.dumps(ordered, indent=2, ensure_ascii=False) + "\n")


def labels_for(issue: dict) -> list[str]:
    """Return the label set for one issue: Jira labels, type, provenance and status."""
    fields = issue["fields"]
    labels = list(fields.get("labels") or [])
    issue_type = (fields.get("issuetype") or {}).get("name")
    if issue_type in TYPE_LABEL:
        labels.append(TYPE_LABEL[issue_type])
    labels.append(IMPORT_LABEL)
    if (fields.get("status") or {}).get("name") == "In Progress":
        labels.append(IN_PROGRESS_LABEL)
    # Deduplicate while keeping a stable order.
    return list(dict.fromkeys(labels))


def create_issue(title: str, body: str, labels: list[str]) -> dict:
    """POST one issue and return the created object, retrying transient failures."""
    payload = json.dumps({"title": title, "body": body, "labels": labels})
    args = ["gh", "api", "--method", "POST", f"repos/{REPO}/issues", "--input", "-"]
    for attempt in range(1, RETRIES + 1):
        result = subprocess.run(args, input=payload, capture_output=True, text=True)
        if result.returncode == 0:
            return json.loads(result.stdout)
        stderr = result.stderr.strip()
        transient = "rate limit" in stderr.lower() or "abuse" in stderr.lower() or "502" in stderr
        if attempt == RETRIES or not transient:
            sys.exit(f"create failed for {title!r} after {attempt} attempt(s):\n{stderr}")
        wait = 20 * attempt
        print(f"    transient failure ({stderr.splitlines()[0][:120]}), retrying in {wait}s")
        time.sleep(wait)
    raise AssertionError("unreachable")


def main() -> int:
    """Create every not-yet-created issue, recording the mapping as we go."""
    export = load_export()
    issues = sorted(export["issues"], key=lambda i: key_number(i["key"]))
    converter = Converter(discover_adr_files(ADR_DIR))

    records = load_mapping()
    done = {record["key"] for record in records}
    todo = [issue for issue in issues if issue["key"] not in done]

    print(f"{len(issues)} issues in export, {len(done)} already created, {len(todo)} to create")
    if not todo:
        print("nothing to do")
        return 0

    for index, issue in enumerate(todo, start=1):
        key = issue["key"]
        title = issue_title(issue)
        body = build_issue_body(issue, export, converter)
        labels = labels_for(issue)

        created = create_issue(title, body, labels)
        records.append(
            {
                "key": key,
                "number": created["number"],
                "node_id": created["node_id"],
                "id": created["id"],
            }
        )
        save_mapping(records)
        print(f"  [{index}/{len(todo)}] {key} -> #{created['number']}  ({len(body)} B, {len(labels)} labels)")

        if index < len(todo):
            time.sleep(THROTTLE_SECONDS)

    print(f"\ncreated {len(todo)} issues; mapping now holds {len(records)} entries")
    numbers = [record["number"] for record in sorted(records, key=lambda r: key_number(r["key"]))]
    if numbers != sorted(numbers):
        print("NOTE: issue numbers are not monotonic in key order (a PR was opened mid-import)")
    print(f"first: {records[0]['key']} -> #{numbers[0]}   last: {records[-1]['key']} -> #{numbers[-1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
