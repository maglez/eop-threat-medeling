#!/usr/bin/env python3
"""Phase 2 of the Jira -> GitHub migration: create the label set.

Reads the label inventory from ``docs/jira-export/meta.json`` (never live Jira, which
is about to lapse) and creates any label that does not already exist on the target
repository.

Three groups of labels are created:

1. The 28 labels actually used in the Jira project, carried across verbatim so that
   existing saved filters and prose that names a label keep working.
2. One type label per Jira issue type (``epic`` / ``story`` / ``task``). GitHub's native
   custom issue types are unavailable here because the repository owner is a user
   rather than an organisation, so the type has to travel as a label.
3. Two provenance/state labels: ``jira-import`` marks every imported issue so the whole
   import can be found or bulk-operated later, and ``status:in-progress`` preserves the
   only status distinction GitHub cannot express. Jira had three statuses; ``Done`` maps
   to closed and ``To Do`` to open, but ``In Progress`` would otherwise be
   indistinguishable from ``To Do`` once both are simply "open".

The script is idempotent: it lists the repository's existing labels first and skips any
name already present, so a label that collides with a GitHub default (``documentation``)
is reused rather than recreated or overwritten. Nothing is ever deleted or renamed.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

REPO = "maglez/eop-threat-modeling"
ROOT = pathlib.Path(__file__).resolve().parents[2]
META = ROOT / "docs" / "jira-export" / "meta.json"

# Colours are cosmetic, but a semantic palette makes the imported backlog scannable.
# Anything not named here falls back to NEUTRAL.
NEUTRAL = "c5def5"
COLOURS = {
    "security": "b60205",
    "defect": "d73a4a",
    "bugfix": "d73a4a",
    "tech-debt": "e99695",
    "blocked-on-owner": "d93f0b",
    "spike": "fef2c0",
    "nice-to-have": "f9d0c4",
}

TYPE_LABELS = {
    "Epic": ("epic", "6f42c1", "Jira issue type: Epic"),
    "Story": ("story", "1d76db", "Jira issue type: Story"),
    "Task": ("task", "0e8a16", "Jira issue type: Task"),
}

EXTRA_LABELS = [
    ("jira-import", "ededed", "Imported from the Jira EOP project (see docs/jira-export)"),
    ("status:in-progress", "fbca04", "Was In Progress in Jira at time of import"),
]


def run(args: list[str]) -> str:
    """Run a command and return stdout, exiting with its stderr on failure."""
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"command failed: {' '.join(args)}\n{result.stderr.strip()}")
    return result.stdout


def existing_labels() -> set[str]:
    """Return the names of every label already on the repository."""
    raw = run(
        [
            "gh",
            "api",
            "--paginate",
            f"repos/{REPO}/labels",
            "--jq",
            ".[].name",
        ]
    )
    return {line.strip() for line in raw.splitlines() if line.strip()}


def create(name: str, colour: str, description: str) -> None:
    """Create a single label."""
    run(
        [
            "gh",
            "api",
            "--method",
            "POST",
            f"repos/{REPO}/labels",
            "-f",
            "name=" + name,
            "-f",
            "color=" + colour,
            "-f",
            "description=" + description,
        ]
    )


def main() -> None:
    meta = json.loads(META.read_text())
    jira_labels = meta["derived"]["labels"]

    wanted: list[tuple[str, str, str]] = []
    for name, count in sorted(jira_labels.items()):
        noun = "issue" if count == 1 else "issues"
        wanted.append((name, COLOURS.get(name, NEUTRAL), f"Jira label ({count} {noun})"))
    for issue_type, (name, colour, description) in sorted(TYPE_LABELS.items()):
        wanted.append((name, colour, description))
    wanted.extend(EXTRA_LABELS)

    present = existing_labels()
    print(f"repository already has {len(present)} labels")

    created = 0
    reused = 0
    for name, colour, description in wanted:
        if name in present:
            print(f"  reuse   {name}")
            reused += 1
            continue
        create(name, colour, description)
        print(f"  create  {name}  #{colour}  {description}")
        created += 1

    print(f"\n{len(wanted)} labels wanted: {created} created, {reused} reused")
    after = existing_labels()
    missing = {name for name, _, _ in wanted} - after
    if missing:
        sys.exit(f"labels still missing after run: {sorted(missing)}")
    print(f"repository now has {len(after)} labels; all wanted labels present")


if __name__ == "__main__":
    main()
