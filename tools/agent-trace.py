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

The listing ends with project-wide totals covering every session ever recorded
for this directory - subagent sessions included, not just the ones listed above.
Cost counts every session. Three durations are reported and only the first is
worked time: `active time` is measured from individual message timestamps, with
any silence longer than the idle cutoff treated as a break, while `calendar span`
and `sessions sum` come from session start/end timestamps and therefore include
every night a session was left open. Then comes a per-gate breakdown of the five
Definition-of-Done gates: how many times each was dispatched, how long it was
in flight, what share of the project window that is, and what it cost. Read the
share as occupancy rather than as a slice of a budget, and see print_gate_totals
for why the rows do not add up.

Options:
    --project PATH        Filter by working directory (default: this repository).
    --limit N             How many sessions to list (default: 10).
    --idle-cutoff MINUTES Silence that ends a block of work (default: 2).
    --json                Emit JSON instead of a report.
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

# How long a silence has to be before it stops counting as work. Two minutes was
# chosen by measurement, not taste: the whole cutoff range was compared against
# the operator's own recollection of their working days, and two minutes was the
# one whose per-day figures matched it. It is deliberately tight - it counts the
# pause while a subagent runs, because that is genuinely working time, and drops
# almost everything else. Every larger cutoff absorbs more real thinking time
# and more coffee breaks alike, which is why the figure is cutoff-sensitive and
# the flag exists rather than the number being buried. See active_time.
DEFAULT_IDLE_CUTOFF_MINUTES = 2.0

# Tools that change the working tree. An agent declaring `edit: deny` that shows
# up here has violated its contract; whether it also defeated the permission
# layer depends on which tool it used, because `edit` is the only write-class
# key any agent actually declares. That last point is inferred from the
# declarations in `.opencode/agents/*.md`, not confirmed against the OpenCode
# runtime — the runtime may withhold the whole write class from an `edit: deny`
# agent, in which case there is no gap. Erring toward the wider check is the
# safe direction. See READ_ONLY_AGENTS below for why it is deliberate.
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
# build and gates the PR. The roster is right, but the arithmetic below cannot
# tell those two dispatches apart, so see MULTI_STAGE_AGENTS.
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

# Agents the pipeline expects in more than one stage. Derived from PIPELINE
# rather than hand-listed, so it cannot drift out of step with the rosters above.
# These are the agents whose mere presence in a trace is not evidence that any
# particular stage was served, because the trace records no stage attribution.
# Under the rosters above this resolves to `architecture-guardian` alone, so the
# plural wording downstream is currently unreachable; it is kept so a second
# multi-stage agent does not silently produce a grammatically wrong finding.
MULTI_STAGE_AGENTS = {
    agent
    for agent in {name for stage in PIPELINE.values() for name in stage}
    if sum(agent in stage for stage in PIPELINE.values()) > 1
}

# The five Definition-of-Done gates, for the per-gate breakdown in the totals
# footer. Derived from PIPELINE rather than hand-listed, following
# MULTI_STAGE_AGENTS above, so a sixth gate added to the roster cannot be
# reported by `conformance` while going missing from the cost and time report.
# AUDITOR_AGENTS below holds the identical five *today* - not merely "the same
# by construction", they are the same members - so do not read the two names as
# evidence of different rosters. They are kept apart because they answer
# different questions, and are expected to diverge: ADR-022 records deriving
# AUDITOR_AGENTS from the agent frontmatter (`permission.edit: deny`) as its
# preferred long-term form, which would make it a property of the agents rather
# than of the pipeline. Until then, a gate that must be reported here but not
# treated as an auditor - or the reverse - is the case that splits them.
GATE_AGENTS = set(PIPELINE["2 gateways"])

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
# deny`. An edit by one of these is a contract violation, and where the tool
# used was `edit` it is a permission failure too — but AUTHORING_TOOLS also
# counts `write`, `patch`, `multiedit` and `notebookedit`, none of which any gate
# frontmatter denies, so this check is deliberately broader than what the
# permission layer actually prevents. It fails safe in the right direction:
# detection over prevention, not detection standing in for prevention.
#
# This is deliberately NOT the same set as AUDITOR_AGENTS. Gate membership means
# "this verdict must be independent of the author — family-independent where the
# invariant holds, and in its two documented exceptions the strongest guarantee
# still available, which is model-independence at best and neither degree where
# the gate and the author resolve to one model ID (Blueprint §3.1)";
# read-only membership means "this agent must never write". Three
# of the five gates legitimately author files — the two testers write tests and
# @architecture-guardian writes ADRs — so folding them into the write check
# would raise a false alarm on every story where a tester does its job, and
# `/trace` documents a read-only RISK as a Sign-off Contract breach worth
# fixing immediately.
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


def format_span(millis: int) -> str:
    """Render a millisecond span as days, hours, minutes and seconds.

    All four units are always printed, zeroes included, so two totals stay
    column-comparable instead of shifting width when a unit drops out.
    """
    seconds = max(int(millis), 0) // 1000
    days, seconds = divmod(seconds, 86400)
    hours, seconds = divmod(seconds, 3600)
    minutes, seconds = divmod(seconds, 60)
    return f"{days}d {hours:02d}h {minutes:02d}m {seconds:02d}s"


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


def project_pattern(project: str) -> str:
    """The LIKE pattern that decides what counts as "this project".

    Factored out so the listing, the newest-root lookup and the totals footer
    cannot drift apart: a footer that summed a different set of sessions than
    the table above it would not reconcile, and nothing would say so.

    Matching on the directory's basename is deliberately loose - it also picks
    up sessions started from a subdirectory, including the `.opencode/agents`
    phantom launch AGENTS.md warns about. Those are still this project's spend.
    """
    return f"%{Path(project).name}%"


def valid_spans(rows: list[sqlite3.Row]) -> list[tuple[int, int]]:
    """The (start, end) span of every row whose timestamps are usable.

    A row with a missing or inverted timestamp cannot contribute a duration, so
    it is dropped here while still counting towards session totals and cost.
    `project_totals` reports how many were dropped, so the gap between the
    session count and the date range is visible rather than silent.
    """
    return [
        (row["time_created"], row["time_updated"])
        for row in rows
        if row["time_created"]
        and row["time_updated"]
        and row["time_updated"] >= row["time_created"]
    ]


def merge_spans(spans: list[tuple[int, int]]) -> int:
    """Milliseconds covered by `spans`, counting any overlap once.

    Two independent effects make plain addition wrong and this collapses both.
    A subagent runs *inside* its parent's span, and the five Definition-of-Done
    gates are dispatched in parallel with each other, so adding spans up
    multi-counts the same wall clock - by 89% for the five gates measured
    together. Merging first is what makes one duration comparable to another.

    Sorts its own input rather than trusting the caller's ORDER BY: the sweep
    below is silently wrong on unsorted spans, and a second caller should not
    have to know that.
    """
    merged: list[list[int]] = []
    for start, end in sorted(spans):
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return sum(end - start for start, end in merged)


def sessionise(stamps: list[int], cutoff: int) -> list[tuple[int, int]]:
    """Group individual message timestamps into blocks of continuous activity.

    A new block starts wherever the silence between two consecutive messages
    exceeds `cutoff`. This is the only honest way to get worked time out of this
    database: a session's own span says nothing about whether anything was
    happening inside it, whereas a gap between messages is direct evidence that
    nothing was.

    Two properties are worth knowing before trusting the result. A block holding
    a single message has zero duration, which is right - one message with silence
    either side is not a measurable stretch of work, though it did happen and is
    counted in the message total. And because this looks only at the gaps, it
    needs no interval merging and no parent/child reasoning: messages from a
    subagent and from the session that dispatched it interleave in one ordered
    stream, so nesting and concurrent windows both collapse for free.
    """
    blocks: list[tuple[int, int]] = []
    ordered = sorted(stamps)
    if not ordered:
        return blocks
    start = previous = ordered[0]
    for stamp in ordered[1:]:
        if stamp - previous > cutoff:
            blocks.append((start, previous))
            start = stamp
        previous = stamp
    blocks.append((start, previous))
    return blocks


def active_time(connection: sqlite3.Connection, project: str, cutoff: int) -> dict:
    """Worked time for this project, measured from message timestamps.

    Every other duration this script reports is derived from `session`, whose two
    timestamps are only first-message-to-last-touch. That made `total time` read
    as most of the calendar: three root sessions left open accounted for eleven
    days between them, and a session opened at night and touched the next morning
    contributed the intervening sleep. The disclaimer under the footer was true
    and useless, because nobody reads a figure labelled as time as meaning "how
    long a tab was open".

    `message` carries one row per message with its own `time_created`, which is
    the evidence that was missing. Sessionising those with a short cutoff gives
    hands-on time, and it came out at roughly a quarter of the span-based figure.

    Days are bucketed in UTC, like every other timestamp here, so a session run
    late in a positive-offset timezone can land on the following day.
    """
    stamps = [
        row[0]
        for row in connection.execute(
            """
            SELECT m.time_created
            FROM message m
            JOIN session s ON s.id = m.session_id
            WHERE s.directory LIKE ? AND m.time_created IS NOT NULL
            ORDER BY m.time_created
            """,
            (project_pattern(project),),
        )
    ]
    blocks = sessionise(stamps, cutoff)
    days = {
        datetime.fromtimestamp(start / 1000, tz=timezone.utc).date()
        for start, _ in blocks
    }
    return {
        "active": sum(end - start for start, end in blocks),
        "blocks": len(blocks),
        "messages": len(stamps),
        "active_days": len(days),
    }


def gate_breakdown(rows: list[sqlite3.Row], window: int) -> list[dict]:
    """Time, occupancy share and cost per Definition-of-Done gate.

    Computed from the same `rows` as the project totals, so a share's numerator
    and denominator can never come from two different reads of a live database.
    Rows are grouped by `canonical()`, so pre-rename `team-member-*` history
    lands under the modern name instead of being reported as a separate agent.

    `share` is occupancy, not a slice of a budget. A gate's span sits inside its
    dispatcher's span, so the non-gate sessions already cover the whole project
    window on their own and these shares are not a partition of anything. Read
    one as "this much of the project's window had that gate in flight".

    The trailing entry merges all five, which is the only honest combined
    figure: the five run concurrently, so their individual times must not be
    added. Costs are per session and *do* add, hence the asymmetry the caller
    spells out.
    """
    breakdown = []
    for name in GATE_AGENTS:
        gate_rows = [row for row in rows if canonical(row["agent"]) == name]
        if not gate_rows:
            continue
        breakdown.append(
            {
                "agent": name,
                "sessions": len(gate_rows),
                "elapsed": merge_spans(valid_spans(gate_rows)),
                "cost": sum(row["cost"] or 0.0 for row in gate_rows),
            }
        )
    breakdown.sort(key=lambda gate: gate["elapsed"], reverse=True)

    every = [row for row in rows if canonical(row["agent"]) in GATE_AGENTS]
    if every:
        # Count the gates actually present rather than hardcoding "five". A gate
        # in the roster that has never been dispatched is skipped above, so a
        # literal would quietly overstate what the row covers.
        breakdown.append(
            {
                "agent": f"all {len(breakdown)} gates, merged",
                "sessions": len(every),
                "elapsed": merge_spans(valid_spans(every)),
                "cost": sum(row["cost"] or 0.0 for row in every),
            }
        )
    for gate in breakdown:
        gate["share"] = gate["elapsed"] / window if window else 0.0
    return breakdown


def project_totals(
    connection: sqlite3.Connection, project: str, cutoff: int
) -> dict:
    """Total cost and elapsed time over *every* session for this project.

    Root and subagent sessions alike, unbounded by --limit.

    Time is the awkward half, and there are two different measurements of it
    here because the `session` table cannot answer the obvious question. Each row
    carries only `time_created` and `time_updated`, so a session's span is
    first-message-to-last-touch: idle minutes count, and a session reopened a
    week later counts the whole week.

    A subagent runs *inside* the session that dispatched it, so its span adds no
    coverage its parent does not already have, and adding it on top counts the
    same wall clock a second time. That containment was checked against this
    project's whole history when the roots-only figure was introduced - every
    completed subagent row fell inside its own parent, nesting one level deep -
    but it is a property of the data, not an invariant this tool enforces, and it
    holds only for sessions that have finished: a subagent still in flight can
    briefly report a `time_updated` past its parent's, because the parent's has
    not been refreshed yet. Re-run the check rather than trusting this paragraph.
    Three figures are reported:

      active   - from `active_time`, i.e. from message timestamps rather than
                 session spans. The one to quote. It is the only figure here that
                 excludes idle time, and it came out far below the other two.
      elapsed  - overlapping spans merged. Because of the containment above this
                 is the root sessions' own coverage, with no subagent time added
                 on top, so it is how much of the calendar this project was open
                 across - not how long it was worked on.
      summed   - the root sessions added up one by one, ignoring their subagents.
                 The literal reading of "how much time did my sessions take".
                 It exceeds elapsed only where two root sessions overlap, which
                 happens whenever two OpenCode windows are open at once.

    Only `active` is worked time. The other two are calendar coverage, and the
    caller says so. `elapsed` stays because the per-gate shares are measured
    against it: a gate's own duration is span-derived too, so dividing it by the
    activity figure would mix two bases and could exceed 100%.
    """
    rows = connection.execute(
        """
        SELECT agent, parent_id, cost, time_created, time_updated
        FROM session
        WHERE directory LIKE ?
        ORDER BY time_created
        """,
        (project_pattern(project),),
    ).fetchall()

    spans = valid_spans(rows)
    elapsed = merge_spans(spans)
    # Root sessions only. A subagent's span sits inside its parent's, so adding
    # subagents in here would count the same wall clock a second time.
    root_rows = [row for row in rows if row["parent_id"] is None]
    root_spans = valid_spans(root_rows)

    return {
        "sessions": len(rows),
        "roots": len(root_rows),
        "subagents": len(rows) - len(root_rows),
        "undated": len(rows) - len(spans),
        # Split out, because only an undated *root* is missing from `summed` -
        # an undated subagent was never a candidate for it.
        "undated_roots": len(root_rows) - len(root_spans),
        "cost": sum(row["cost"] or 0.0 for row in rows),
        "elapsed": elapsed,
        "summed": sum(end - start for start, end in root_spans),
        "first": min((start for start, _ in spans), default=None),
        "last": max((end for _, end in spans), default=None),
        "cutoff": cutoff,
        **active_time(connection, project, cutoff),
        "gates": gate_breakdown(rows, elapsed),
    }


def print_gate_totals(totals: dict) -> None:
    """The per-gate block that sits under the project totals.

    The closing caveat is not decoration. Percentages invite addition far more
    strongly than durations do, and adding the per-gate shares comes to roughly
    twice the true merged figure - so the line saying they do not add is what
    stops the table being read wrongly. No exact ratio is quoted here on
    purpose: it moves with the data, and a stale literal in a docstring is
    exactly the drift this tool exists to expose elsewhere.
    """
    gates = totals["gates"]
    if not gates:
        return
    print(f"\n  Definition-of-Done gates, against the"
          f" {format_span(totals['elapsed'])} calendar span above\n")
    print(f"  {'gate':<24}{'sessions':>9}{'time':>18}{'share':>8}{'cost':>13}")
    for gate in gates:
        cost = f"${gate['cost']:,.2f}"
        print(f"  {gate['agent']:<24}{gate['sessions']:>9}"
              f"{format_span(gate['elapsed']):>18}{gate['share']:>8.1%}"
              f"{cost:>13}")
    print(
        "\n  The gates run in parallel, so their times and shares overlap and do"
        " not add up\n  to the merged row - only the costs do. A share is"
        " occupancy: how much of the\n  window had that gate in flight."
        " architecture-guardian is also dispatched\n  outside the gate stage,"
        " so its row includes ADR work as well as reviews."
    )


def format_cutoff(cutoff: int) -> str:
    """Render the idle cutoff the way it was typed, not in milliseconds."""
    minutes = cutoff / 60_000
    return f"{minutes:g}m" if minutes >= 1 else f"{cutoff / 1000:g}s"


def print_project_totals(
    connection: sqlite3.Connection, project: str, cutoff: int
) -> None:
    totals = project_totals(connection, project, cutoff)
    if not totals["sessions"]:
        return
    gap = format_cutoff(totals["cutoff"])
    print(f"\nAll {totals['sessions']} sessions ever recorded for this project"
          f" ({totals['roots']} root + {totals['subagents']} subagent)\n")
    print(f"  total cost   : ${totals['cost']:,.2f}")
    print(f"  active time  : {format_span(totals['active'])}"
          f"   worked time, silences over {gap} dropped")
    print(f"  calendar span: {format_span(totals['elapsed'])}"
          "   first message to last touch, idle included")
    print(f"  sessions sum : {format_span(totals['summed'])}"
          f"   the {totals['roots']} root sessions added up, overlap and all")
    print(f"  first / last : {when(totals['first'])} -> {when(totals['last'])}")
    if totals["undated"]:
        # Only an undated root is missing from the sum, so name that count when
        # there is one rather than leaving the reader to wonder.
        of_which = (f", {totals['undated_roots']} of them root sessions"
                    if totals["undated_roots"] else "")
        print(f"  undated      : {totals['undated']} session(s) with unusable"
              f" timestamps{of_which}, counted in cost but in no time figure")
    print(
        f"\n  active time is the one to quote. It reads {totals['messages']:,}"
        f" message timestamps and\n  counts a silence longer than {gap} as a"
        " break, so it spans"
        f" {totals['active_days']} days\n  rather than the whole calendar."
        " Raise or lower it with --idle-cutoff MINUTES;\n  a larger cutoff"
        " absorbs more thinking time and more coffee breaks alike."
    )
    print(
        "\n  The two figures under it are coverage, not work: a session left open"
        " overnight\n  counts the night. They are kept because the per-gate"
        " shares below divide one\n  span-derived duration by another, which"
        " mixing in the activity figure would\n  break. The"
        f" {totals['subagents']} subagent sessions are in neither of them, on"
        " purpose:\n  each runs inside the session that dispatched it, so its"
        " parent's span covers it."
    )
    print_gate_totals(totals)


def list_sessions(
    connection: sqlite3.Connection, project: str, limit: int, cutoff: int
) -> None:
    rows = connection.execute(
        """
        SELECT s.*, (SELECT count(*) FROM session c WHERE c.parent_id = s.id) AS kids
        FROM session s
        WHERE s.parent_id IS NULL AND s.directory LIKE ?
        ORDER BY s.time_created DESC
        LIMIT ?
        """,
        (project_pattern(project), limit),
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
    print_project_totals(connection, project, cutoff)


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
        (project_pattern(project),),
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
        # `ran` carries no stage attribution: the trace records that an agent was
        # dispatched, never which pipeline stage the dispatch served. So an agent
        # listed in two stages is credited to both on the strength of a single
        # dispatch, and this stage's evidence is not proof it ran *here*. Say so
        # rather than printing an unqualified OK over evidence that cannot bear
        # it — @architecture-guardian authors ADRs at stage 1 and gates the PR at
        # stage 2, so one build-time dispatch would otherwise report stage 2
        # complete while that gate never reviewed the work at all.
        ambiguous = [agent for agent in present if agent in MULTI_STAGE_AGENTS]
        caveat = (
            f" (unattributable: {', '.join(ambiguous)}"
            f" also {'belongs' if len(ambiguous) == 1 else 'belong'} to another"
            " stage, so this may be one dispatch counted twice)"
            if ambiguous
            else ""
        )
        # Two ways a stage can have no evidence worth the name. Nobody ran, or
        # everyone who ran is unattributable — and the second must be as loud as
        # the first. If every agent credited to this stage also belongs to
        # another one, the trace contains nothing that places any dispatch here,
        # so reporting PART would let the worst case (a build-time ADR dispatch
        # standing in for five gate reviews that never happened) hide behind a
        # severity readers are told not to act on. MISS is the honest severity;
        # the message says which agent was seen so the reader is not misled into
        # thinking the trace was empty.
        if not present:
            findings.append(f"MISS  stage {stage}: none of {', '.join(expected)} ran")
        elif present == ambiguous:
            unattributable = (
                f"MISS  stage {stage}: no attributable evidence — only"
                f" {', '.join(present)} ran, and"
                f" {'that dispatch may' if len(present) == 1 else 'those dispatches may'}"
                f" belong to another stage"
            )
            findings.append(
                f"{unattributable}; absent {', '.join(missing)}"
                if missing
                else unattributable
            )
        elif missing:
            findings.append(
                f"PART  stage {stage}: ran {', '.join(present)};"
                f" absent {', '.join(missing)}{caveat}"
            )
        elif ambiguous:
            findings.append(f"PART  stage {stage}: ran {', '.join(present)}{caveat}")
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

    # An agent whose definition denies `edit` but which edited files has broken
    # its Sign-off Contract. Whether it also defeated the permission layer
    # depends on which tool it used — see AUTHORING_TOOLS. Only the strictly
    # read-only gates are checked here; see READ_ONLY_AGENTS for why this is
    # not AUDITOR_AGENTS.
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
    parser.add_argument("--limit", type=int, default=10, help="sessions to list")
    parser.add_argument(
        "--idle-cutoff",
        type=float,
        default=DEFAULT_IDLE_CUTOFF_MINUTES,
        metavar="MINUTES",
        help="silence that ends a block of work"
             f" (default: {DEFAULT_IDLE_CUTOFF_MINUTES:g})",
    )
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args()

    if args.idle_cutoff <= 0:
        parser.error("--idle-cutoff must be greater than zero")

    project = args.project or repo_root()
    connection = connect()
    try:
        if args.session:
            root = resolve(connection, args.session)
        elif args.last:
            root = newest_root(connection, project)
        else:
            list_sessions(
                connection, project, args.limit, int(args.idle_cutoff * 60_000)
            )
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
