#!/usr/bin/env python3
"""Render how a piece of work was actually delivered by the agent team.

OpenCode already records every dispatch: each subagent runs in its own child
session, and `~/.local/share/opencode/opencode.db` stores the parent link, the
agent name, the model, the cost and every tool call. Nothing needs
instrumenting - this script only reads and renders what is already there.

Two things are worth looking at, and the second is the point of the script:

  1. The shape of the delivery - who dispatched whom, in what order.
  2. Whether the pipeline in .opencode/agents/tech-lead.md was actually
     followed, and whether the Separation Invariant held. A story whose code
     was authored by the same model that later cleared it has had a review in
     name only, and that is invisible in a diagram but obvious in a table.

The database is opened read-only. This script never writes to it.

Usage:
    tools/agent-trace.py                    List recent root sessions.
    tools/agent-trace.py <session-id>       Trace one session (id or prefix).
    tools/agent-trace.py --last             Trace the most recent root session.
    tools/agent-trace.py --last --json      Machine-readable form.

Options:
    --project PATH   Filter by working directory (default: this repository).
    --limit N        How many sessions to list (default: 20).
    --json           Emit JSON instead of a report.
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = Path.home() / ".local" / "share" / "opencode" / "opencode.db"

# Tools that change the working tree. An agent declaring `edit: deny` that
# shows up here is a permission failure, not a style question.
AUTHORING_TOOLS = {"edit", "write", "patch", "multiedit", "notebookedit"}

# Built-in agents. They are not team members and should not be judged
# against the delivery pipeline.
BUILTIN_AGENTS = {
    "build",
    "plan",
    "general",
    "explore",
    "scout",
    "compaction",
    "title",
    "summary",
}

# The pipeline from .opencode/agents/tech-lead.md, by stage. Stage 2 is the five
# Definition-of-Done gates and nothing else: performance-engineer is advisory and
# carries no Sign-off Contract, so dispatching it as a gate is unsupported.
# architecture-guardian appears in both stages by design — it authors ADRs during
# build and gates the PR — so it is not double-counted in error.
PIPELINE = {
    "0 requirements": ["product-owner"],
    "1 build": [
        "architecture-guardian",
        "db-designer",
        "devops-engineer",
        "ui-builder",
    ],
    "2 gateways": [
        "tester-unit-and-quality",
        "tester-api",
        "security-auditor",
        "code-reviewer",
        "architecture-guardian",
    ],
}

# Agents whose job is to judge someone else's work. If one of these ran on the
# same model as whoever authored the code, the review was self-review.
#
# This set must stay in step with the five Definition-of-Done gates in
# AGENTS.md. Both testers belong here: they are gates first and authors second,
# so a tester sharing a model with the author of the code under test is exactly
# the condition this detector exists to catch. Omitting them left the check
# blind to two of the five gates. `performance-engineer` is deliberately absent
# — it is not a DoD gate.
AUDITOR_AGENTS = {
    "code-reviewer",
    "security-auditor",
    "architecture-guardian",
    "tester-api",
    "tester-unit-and-quality",
}

# A *strictly* read-only agent: one whose definition declares `permission.edit:
# deny`, so any edit it makes is a permission failure. This is deliberately NOT
# the same set as AUDITOR_AGENTS. Gate membership means "this verdict must be
# independent of the author — family-independent where the invariant holds, at
# worst model-independent in its two documented exceptions (Blueprint §3.1)";
# read-only membership means "this agent must never write". Three
# of the five gates legitimately author files — the two testers write tests and
# @architecture-guardian writes ADRs — so folding them into the write check
# would raise a false alarm on every story where a tester does its job, and
# `/trace` documents a read-only RISK as an urgent configuration defect.
#
# Scope: the DoD gates that declare `permission.edit: deny`, and only those. The
# four advisory experts declare it too but are not gates and never carry a
# verdict, so they are out of scope here rather than missing. Keep this set in
# step with the gate frontmatter in .opencode/agents/; ADR-022 records deriving
# it from that frontmatter as the preferred long-term form.
READ_ONLY_AGENTS = {
    "code-reviewer",
    "security-auditor",
}


def canonical(agent: str | None) -> str:
    """Return an agent name that is stable across the EOP-000 rename.

    The `agent` column stores the name as it was when the session ran, so
    history written before commit 93f3e1d still says `team-member-code-reviewer`.
    Grouping on the raw value would report one agent as two.
    """
    if not agent:
        return "(none)"
    return agent[len("team-member-") :] if agent.startswith("team-member-") else agent


def repo_root() -> str:
    """Return the git top level, falling back to the current directory."""
    try:
        found = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=True,
        )
        return found.stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return os.getcwd()


def connect() -> sqlite3.Connection:
    if not DB_PATH.exists():
        sys.exit(f"No OpenCode database at {DB_PATH}")
    connection = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def model_of(row: sqlite3.Row) -> str:
    """Extract a readable model id from the JSON blob in `session.model`."""
    raw = row["model"]
    if not raw:
        return "(unset)"
    try:
        parsed = json.loads(raw)
    except (TypeError, ValueError):
        return str(raw)[:40]
    identifier = parsed.get("id") or parsed.get("modelID") or str(parsed)
    # Provider prefixes and region qualifiers add width without meaning here.
    return str(identifier).split("/")[-1].removeprefix("eu.").removeprefix("us.")


def when(millis: int | None) -> str:
    if not millis:
        return "-"
    return datetime.fromtimestamp(millis / 1000, tz=timezone.utc).strftime(
        "%Y-%m-%d %H:%M"
    )


def duration(row: sqlite3.Row) -> str:
    start, end = row["time_created"], row["time_updated"]
    if not start or not end or end < start:
        return "-"
    seconds = (end - start) / 1000
    if seconds < 90:
        return f"{seconds:.0f}s"
    return f"{seconds / 60:.0f}m"


def tool_counts(connection: sqlite3.Connection, session_id: str) -> Counter[str]:
    """Count tool invocations in a session.

    `part.data` is a JSON blob rather than columns, so the tool name has to be
    extracted. This is what makes the conformance check possible: it shows what
    an agent actually did, not what its prompt says it does.
    """
    rows = connection.execute(
        """
        SELECT json_extract(data, '$.tool') AS tool, count(*) AS uses
        FROM part
        WHERE session_id = ? AND json_extract(data, '$.type') = 'tool'
        GROUP BY tool
        """,
        (session_id,),
    ).fetchall()
    return Counter({row["tool"]: row["uses"] for row in rows if row["tool"]})


def descendants(connection: sqlite3.Connection, root_id: str) -> list[sqlite3.Row]:
    """Walk the dispatch tree breadth-first from a root session.

    Subagents can dispatch subagents - the Tech Lead is meant to - so this
    cannot assume a single level.
    """
    collected: list[sqlite3.Row] = []
    seen = {root_id}
    frontier = [root_id]
    while frontier:
        placeholders = ",".join("?" * len(frontier))
        children = connection.execute(
            f"SELECT * FROM session WHERE parent_id IN ({placeholders})"
            " ORDER BY time_created",
            frontier,
        ).fetchall()
        frontier = []
        for child in children:
            if child["id"] in seen:
                continue
            seen.add(child["id"])
            collected.append(child)
            frontier.append(child["id"])
    return collected


def configured_models(project: str) -> dict[str, str]:
    """Read the agent-to-model pinning from .opencode/opencode.json.

    Reported alongside what actually ran, because a key that no longer matches
    an agent file silently falls back to the global default with no error.
    """
    config = Path(project) / ".opencode" / "opencode.json"
    if not config.exists():
        return {}
    try:
        parsed = json.loads(config.read_text())
    except ValueError:
        return {}
    pinned = {}
    for name, settings in (parsed.get("agent") or {}).items():
        if isinstance(settings, dict) and settings.get("model"):
            pinned[canonical(name)] = str(settings["model"])
    return pinned


def list_sessions(connection: sqlite3.Connection, project: str, limit: int) -> None:
    rows = connection.execute(
        """
        SELECT s.*, (SELECT count(*) FROM session c WHERE c.parent_id = s.id) AS kids
        FROM session s
        WHERE s.parent_id IS NULL AND s.directory LIKE ?
        ORDER BY s.time_created DESC
        LIMIT ?
        """,
        (f"%{Path(project).name}%", limit),
    ).fetchall()
    if not rows:
        sys.exit(f"No root sessions found for a directory matching {project!r}")

    print(f"Root sessions in {project}\n")
    print(f"{'created':<17} {'agent':<16} {'sub':>4} {'cost':>9}  id / title")
    print("-" * 100)
    for row in rows:
        title = (row["title"] or "").replace("\n", " ")[:44]
        print(
            f"{when(row['time_created']):<17} {canonical(row['agent'])[:16]:<16}"
            f" {row['kids']:>4} {row['cost'] or 0:>9.2f}  {row['id'][:24]}  {title}"
        )
    print("\nTrace one with: tools/agent-trace.py <id>")


def resolve(connection: sqlite3.Connection, wanted: str) -> sqlite3.Row:
    row = connection.execute("SELECT * FROM session WHERE id = ?", (wanted,)).fetchone()
    if row:
        return row
    matches = connection.execute(
        "SELECT * FROM session WHERE id LIKE ? ORDER BY time_created DESC LIMIT 5",
        (f"{wanted}%",),
    ).fetchall()
    if not matches:
        sys.exit(f"No session matching {wanted!r}")
    if len(matches) > 1:
        joined = "\n  ".join(f"{m['id']}  {m['title'] or ''}"[:90] for m in matches)
        sys.exit(f"{wanted!r} is ambiguous:\n  {joined}")
    return matches[0]


def newest_root(connection: sqlite3.Connection, project: str) -> sqlite3.Row:
    row = connection.execute(
        """
        SELECT * FROM session
        WHERE parent_id IS NULL AND directory LIKE ?
        ORDER BY time_created DESC LIMIT 1
        """,
        (f"%{Path(project).name}%",),
    ).fetchone()
    if not row:
        sys.exit(f"No root session found for {project!r}")
    return row


def build_trace(
    connection: sqlite3.Connection, root: sqlite3.Row, project: str
) -> dict:
    children = descendants(connection, root["id"])
    depths = {root["id"]: 0}
    nodes = []
    for row in [root] + children:
        parent = row["parent_id"]
        depths[row["id"]] = 0 if parent is None else depths.get(parent, 0) + 1
        tools = tool_counts(connection, row["id"])
        nodes.append(
            {
                "id": row["id"],
                "parent": parent,
                "depth": depths[row["id"]],
                "agent": canonical(row["agent"]),
                "model": model_of(row),
                "title": (row["title"] or "").replace("\n", " "),
                "created": when(row["time_created"]),
                "duration": duration(row),
                "cost": row["cost"] or 0.0,
                "tokens_out": row["tokens_output"] or 0,
                "tools": dict(tools.most_common()),
                "authoring": sum(
                    count for tool, count in tools.items() if tool in AUTHORING_TOOLS
                ),
                "total_tools": sum(tools.values()),
            }
        )
    return {
        "root": root["id"],
        "project": project,
        "pinned": configured_models(project),
        "nodes": nodes,
    }


def mermaid(trace: dict) -> str:
    lines = ["```mermaid", "graph TD"]
    for node in trace["nodes"]:
        short = node["id"][4:14] if node["id"].startswith("ses_") else node["id"][:10]
        wrote = f"<br/>{node['authoring']} edits" if node["authoring"] else ""
        label = (
            f"{node['agent']}<br/><small>{node['model']}</small>"
            f"<br/>${node['cost']:.2f}{wrote}"
        )
        lines.append(f'    {short}["{label}"]')
    for node in trace["nodes"]:
        if not node["parent"]:
            continue
        parent = node["parent"]
        child = node["id"]
        head = parent[4:14] if parent.startswith("ses_") else parent[:10]
        tail = child[4:14] if child.startswith("ses_") else child[:10]
        lines.append(f"    {head} --> {tail}")
    lines.append("```")
    return "\n".join(lines)


def conformance(trace: dict) -> list[str]:
    """Judge the trace against the pipeline and the Separation Invariant."""
    findings: list[str] = []
    ran = {node["agent"] for node in trace["nodes"]}
    team = {agent for agent in ran if agent not in BUILTIN_AGENTS}

    for stage, expected in PIPELINE.items():
        present = [agent for agent in expected if agent in ran]
        missing = [agent for agent in expected if agent not in ran]
        if not present:
            findings.append(f"MISS  stage {stage}: none of {', '.join(expected)} ran")
        elif missing:
            findings.append(
                f"PART  stage {stage}: ran {', '.join(present)};"
                f" absent {', '.join(missing)}"
            )
        else:
            findings.append(f"OK    stage {stage}: {', '.join(present)}")

    if not team:
        findings.append(
            "MISS  no team agent ran at all - this work was done by the primary"
            " agent alone, so no second model saw it"
        )

    # Who changed the working tree, and on which model.
    authors = {
        (node["agent"], node["model"])
        for node in trace["nodes"]
        if node["authoring"] > 0
    }
    auditors = {
        (node["agent"], node["model"])
        for node in trace["nodes"]
        if node["agent"] in AUDITOR_AGENTS
    }
    author_models = {model for _, model in authors}
    for agent, model in sorted(auditors):
        if model in author_models:
            culprits = sorted(a for a, m in authors if m == model)
            findings.append(
                f"RISK  {agent} audited on {model}, which also authored"
                f" ({', '.join(culprits)}) - same model, so this is self-review"
            )

    # An agent whose definition denies `edit` but which edited files is a
    # permission failure. Only the strictly read-only gates are checked here;
    # see READ_ONLY_AGENTS for why this is not AUDITOR_AGENTS.
    for node in trace["nodes"]:
        if node["agent"] in READ_ONLY_AGENTS and node["authoring"] > 0:
            findings.append(
                f"RISK  {node['agent']} made {node['authoring']} edits;"
                " its role is read-only"
            )

    # A gate that wrote files in the same dispatch that returned a verdict has
    # approved a tree it changed. That is legitimate when the brief authorised a
    # documentation write, so it is INFO rather than RISK — but it must be
    # visible, because the three gates that may write declare no `edit: deny`
    # and so are invisible to the read-only check above. The trace records edits
    # but not verdicts, so this cannot tell an authoring-only dispatch from a
    # gating one; the message is worded to leave that to the reader rather than
    # asserting a verdict was returned.
    for node in trace["nodes"]:
        if (
            node["agent"] in AUDITOR_AGENTS
            and node["agent"] not in READ_ONLY_AGENTS
            and node["authoring"] > 0
        ):
            findings.append(
                f"INFO  {node['agent']} made {node['authoring']} edits; if that"
                " dispatch also returned a verdict, the verdict attaches to a"
                " tree it changed"
            )

    pinned = trace["pinned"]
    if pinned:
        for node in trace["nodes"]:
            if node["agent"] in BUILTIN_AGENTS:
                continue
            if node["agent"] not in pinned:
                findings.append(
                    f"WARN  {node['agent']} has no model pinned in"
                    " .opencode/opencode.json, so it used the global default"
                )
    return findings


def report(trace: dict) -> None:
    root = trace["nodes"][0]
    print(f"Delivery trace for {trace['root']}")
    print(f"  {root['title']}")
    print(f"  primary agent : {root['agent']} on {root['model']}")
    print(f"  started       : {root['created']}")
    subagents = len(trace["nodes"]) - 1
    total = sum(node["cost"] for node in trace["nodes"])
    print(f"  subagents     : {subagents}")
    print(f"  total cost    : ${total:.2f}\n")

    print("## Dispatch tree\n")
    print(mermaid(trace))

    print("\n## Who did what\n")
    header = (
        f"{'':<2} {'agent':<26} {'model':<24} {'time':>6} {'cost':>8}"
        f" {'edits':>6}  tools"
    )
    print(header)
    print("-" * len(header))
    for node in trace["nodes"]:
        indent = "  " * node["depth"]
        top = ", ".join(f"{t}x{c}" for t, c in list(node["tools"].items())[:4]) or "-"
        print(
            f"{indent:<2} {node['agent'][:26]:<26} {node['model'][:24]:<24}"
            f" {node['duration']:>6} {node['cost']:>8.2f} {node['authoring']:>6}"
            f"  {top}"
        )

    print("\n## Pipeline conformance\n")
    for finding in conformance(trace):
        print(f"  {finding}")
    print()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Show how work was delivered across the agent team.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("session", nargs="?", help="session id or unique prefix")
    parser.add_argument("--last", action="store_true", help="trace the newest session")
    parser.add_argument("--project", default=None, help="working directory to filter by")
    parser.add_argument("--limit", type=int, default=20, help="sessions to list")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args()

    project = args.project or repo_root()
    connection = connect()
    try:
        if args.session:
            root = resolve(connection, args.session)
        elif args.last:
            root = newest_root(connection, project)
        else:
            list_sessions(connection, project, args.limit)
            return
        trace = build_trace(connection, root, project)
        if args.json:
            print(json.dumps(trace, indent=2))
        else:
            report(trace)
    finally:
        connection.close()


if __name__ == "__main__":
    main()
