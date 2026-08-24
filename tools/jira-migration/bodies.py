"""Assemble GitHub issue and comment bodies from the committed Jira export.

Every body this module produces is built from ``docs/jira-export/*.json`` and
never from a live Jira call, so the whole migration stays reproducible after the
Jira account lapses.  Run as a script it renders previews to ``.tmp/jira-preview/``
and asserts the invariants the import depends on; imported as a module it is the
body builder used by both import passes.

Three things Jira models natively have no GitHub equivalent and are therefore
rendered into the body text:

    * original metadata -- GitHub sets ``created``, ``updated`` and the author
      from the API call itself, so the real reporter and timestamps can only
      survive as prose;
    * typed issue links -- GitHub has one untyped reference, so the 69 link
      pairs become a Relationships section that names each link type;
    * parent and child -- recorded in prose as well as wired through the native
      sub-issues API in the second pass, so the hierarchy survives even if
      someone later unlinks the sub-issues.

Previews are written under ``.tmp/`` rather than ``docs/`` on purpose.  The
repository runs prose as a build gate: ``DeckArithmeticClaimsTest``,
``SourceCitationAnchorTest`` and ``MermaidSequenceTextTest`` walk
``docs/**/*.md`` and would fail ``./mvnw verify`` on imported Jira prose, which
is full of the deck-arithmetic phrasings, bare ``Foo.java:123`` citations and
Mermaid labels they forbid.  The export directory holds ``.json`` only, which
every one of those tests filters out by extension.
"""

from __future__ import annotations

import json
import re
import sys
from datetime import UTC, datetime
from pathlib import Path

from adf_to_markdown import (
    KEY_PATTERN,
    Converter,
    discover_adr_files,
    verify_corpus_assumptions,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
EXPORT_DIR = REPO_ROOT / "docs" / "jira-export"
PREVIEW_DIR = REPO_ROOT / ".tmp" / "jira-preview"
ADR_DIR = REPO_ROOT / "docs" / "adr"
JIRA_BASE = "https://maglez.atlassian.net"

# Ordered so that the strongest relationship reads first.
LINK_SECTIONS = (
    ("Blocks", "outward", "Blocks"),
    ("Blocks", "inward", "Blocked by"),
    ("Duplicate", "outward", "Duplicates"),
    ("Duplicate", "inward", "Duplicated by"),
    ("Relates", "outward", "Relates to"),
    ("Relates", "inward", "Relates to"),
)


def load_export() -> dict:
    """Read the four export files.

    :return: a dict with ``issues`` (list), ``by_key`` (dict), ``comments``
        (dict keyed by issue key), ``links`` (list) and ``meta`` (dict).
    """
    issues = json.loads((EXPORT_DIR / "issues.json").read_text())
    comments = json.loads((EXPORT_DIR / "comments.json").read_text())
    links = json.loads((EXPORT_DIR / "links.json").read_text())
    meta = json.loads((EXPORT_DIR / "meta.json").read_text())
    return {
        "issues": issues,
        "by_key": {i["key"]: i for i in issues},
        "comments": comments,
        "links": links,
        "meta": meta,
    }


def format_timestamp(raw: str | None) -> str:
    """Render a Jira timestamp as a compact UTC string.

    :param raw: an ISO-8601 timestamp from Jira, or ``None``.
    :return: e.g. ``2026-08-16 13:22 UTC``, or ``unknown`` when absent.
    """
    if not raw:
        return "unknown"
    try:
        return datetime.fromisoformat(raw).astimezone(UTC).strftime("%Y-%m-%d %H:%M UTC")
    except ValueError:
        return raw


def display_name(actor: dict | None) -> str:
    """Return a Jira user's display name, tolerating deleted or absent users."""
    if not actor:
        return "unknown"
    return actor.get("displayName") or actor.get("emailAddress") or "unknown"


def issue_title(issue: dict) -> str:
    """Build the GitHub issue title.

    The Jira key is kept in the title because GitHub issue numbers cannot be
    aligned with it -- 125 pull requests already consumed the numbering
    sequence -- and the repository's existing commits and pull requests already
    cite keys in this ``[EOP-NNN]`` form.

    :param issue: a raw Jira issue from the export.
    :return: the title, truncated to GitHub's 256-character limit.
    """
    summary = " ".join((issue["fields"].get("summary") or "").split())
    title = f"[{issue['key']}] {summary}"
    if len(title) > 256:
        title = title[:253].rstrip() + "..."
    return title


def relationship_lines(issue: dict, export: dict, converter: Converter) -> list[str]:
    """Build the Relationships section for one issue.

    :param issue: the raw Jira issue.
    :param export: the loaded export, per :func:`load_export`.
    :param converter: the converter, used so keys render identically to prose.
    :return: Markdown lines, empty when the issue has no relationships.
    """
    key = issue["key"]
    fields = issue["fields"]
    grouped: dict[str, list[str]] = {}

    parent = fields.get("parent")
    if parent:
        grouped["Parent"] = [converter.key_reference(parent["key"])]

    children = sorted(
        (
            i["key"]
            for i in export["issues"]
            if (i["fields"].get("parent") or {}).get("key") == key
        ),
        key=lambda k: int(k.split("-")[1]),
    )
    if children:
        grouped["Child issues"] = [converter.key_reference(c) for c in children]

    for link_type, direction, heading in LINK_SECTIONS:
        matches = []
        for link in export["links"]:
            if link["type"] != link_type:
                continue
            if direction == "outward" and link["from"] == key:
                matches.append(link["to"])
            elif direction == "inward" and link["to"] == key:
                matches.append(link["from"])
        if matches:
            refs = [converter.key_reference(m) for m in sorted(matches, key=lambda k: int(k.split("-")[1]))]
            grouped.setdefault(heading, []).extend(refs)

    if not grouped:
        return []

    lines = ["## Relationships", ""]
    for heading, refs in grouped.items():
        lines.append(f"- **{heading}:** {', '.join(refs)}")
    return lines


def provenance_lines(issue: dict) -> list[str]:
    """Build the provenance footer recording what GitHub's API cannot carry."""
    fields = issue["fields"]
    key = issue["key"]
    parts = [
        f"**{key}**",
        fields["issuetype"]["name"],
        fields["status"]["name"],
        f"reported by {display_name(fields.get('reporter'))}",
        f"created {format_timestamp(fields.get('created'))}",
        f"updated {format_timestamp(fields.get('updated'))}",
    ]
    if fields.get("resolutiondate"):
        parts.append(f"resolved {format_timestamp(fields['resolutiondate'])}")
    labels = fields.get("labels") or []
    if labels:
        parts.append("labels " + ", ".join(f"`{label}`" for label in sorted(labels)))
    return [
        "---",
        "",
        "<sub>Imported from Jira: " + " &middot; ".join(parts) + ".",
        f"Original URL `{JIRA_BASE}/browse/{key}` (site decommissioned, retained for provenance). "
        "Full fidelity source in `docs/jira-export/issues.json`.</sub>",
    ]


def build_issue_body(issue: dict, export: dict, converter: Converter) -> str:
    """Render a complete GitHub issue body.

    :param issue: the raw Jira issue.
    :param export: the loaded export.
    :param converter: the ADF converter, carrying the cross-reference mapping.
    :return: the Markdown body.
    """
    description = issue["fields"].get("description")
    sections: list[str] = []
    if description:
        sections.append(converter.convert(description))
    else:
        sections.append("_No description in Jira._")
    relationships = relationship_lines(issue, export, converter)
    if relationships:
        sections.append("\n".join(relationships))
    sections.append("\n".join(provenance_lines(issue)))
    return "\n\n".join(sections).strip() + "\n"


def build_comment_body(comment: dict, converter: Converter) -> str:
    """Render a GitHub comment body from a Jira comment.

    :param comment: a raw Jira comment.
    :param converter: the ADF converter.
    :return: the Markdown body.
    """
    body = comment.get("body")
    text = converter.convert(body) if body else "_Empty comment in Jira._"
    footer = (
        f"<sub>Imported from Jira: comment by {display_name(comment.get('author'))} "
        f"on {format_timestamp(comment.get('created'))}"
    )
    if comment.get("updated") and comment["updated"] != comment.get("created"):
        footer += f", edited {format_timestamp(comment['updated'])}"
    footer += ".</sub>"
    return f"{text}\n\n---\n\n{footer}\n"


def main() -> int:
    """Render every body to the preview directory and check the invariants."""
    export = load_export()
    issues = export["issues"]
    adr_files = discover_adr_files(ADR_DIR)
    converter = Converter(adr_files)
    known_keys = set(export["by_key"])

    print(f"loaded {len(issues)} issues, {len(adr_files)} ADR files")

    structural: list[str] = []
    for issue in issues:
        description = issue["fields"].get("description")
        if description:
            structural += [f"{issue['key']} description: {p}" for p in verify_corpus_assumptions(description)]
    for key, comments in export["comments"].items():
        for index, comment in enumerate(comments):
            if comment.get("body"):
                structural += [
                    f"{key} comment {index}: {p}" for p in verify_corpus_assumptions(comment["body"])
                ]

    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    for stale in PREVIEW_DIR.glob("*.md"):
        stale.unlink()

    bodies: dict[str, str] = {}
    comment_count = 0
    for issue in issues:
        key = issue["key"]
        body = build_issue_body(issue, export, converter)
        bodies[key] = body
        parts = [f"# {issue_title(issue)}", "", body]
        for index, comment in enumerate(export["comments"].get(key, []), start=1):
            comment_count += 1
            parts += [f"<!-- comment {index} -->", "", build_comment_body(comment, converter)]
        (PREVIEW_DIR / f"{key}.md").write_text("\n".join(parts))

    joined = "\n".join(bodies.values())
    referenced = set(KEY_PATTERN.findall(joined))
    dangling = sorted(referenced - known_keys, key=lambda k: int(k.split("-")[1]))
    surviving_jira = len(re.findall(r"https?://\S*atlassian\.net/browse", joined))
    expected_provenance = len(issues)

    print(f"rendered {len(bodies)} issue bodies and {comment_count} comments to {PREVIEW_DIR}")
    print("\nconverter stats:")
    for name, count in sorted(converter.stats.items()):
        print(f"  {name:34} {count}")

    print("\nchecks:")
    print(f"  structural violations              {len(structural)}")
    print(f"  distinct EOP keys referenced       {len(referenced)}")
    print(f"  keys with no imported issue        {len(dangling)} {dangling if dangling else ''}")
    print(f"  ADR references with no file        {sum(converter.missing_adrs.values())} "
          f"{dict(converter.missing_adrs) if converter.missing_adrs else ''}")
    print(f"  surviving Jira browse URLs         {surviving_jira} (expected {expected_provenance}, "
          "one provenance line per issue)")

    if structural:
        print("\nSTRUCTURAL VIOLATIONS (converter scope exceeded):")
        for problem in structural[:20]:
            print(f"  {problem}")
        return 1
    if surviving_jira != expected_provenance:
        print("\nUnexpected number of Jira URLs: only the provenance footers should carry one.")
        return 1
    print("\nOK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
