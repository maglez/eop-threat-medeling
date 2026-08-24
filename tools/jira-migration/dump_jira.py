#!/usr/bin/env python3
"""Phase 0 of EOP-166: full-fidelity raw export of the Jira EOP project.

Writes JSON only, into docs/jira-export/. Never Markdown: the repository's
prose build gates (DeckArithmeticClaimsTest, SourceCitationAnchorTest,
MermaidSequenceTextTest) walk docs/**/*.md and would scan imported Jira prose.

Everything downstream of this script reads its output, never Jira, so that the
migration survives the account expiring.

Usage:  python3 tools/jira-migration/dump_jira.py
Env:    JIRA_URL, JIRA_USERNAME, JIRA_API_TOKEN, JIRA_PROJECT_KEY (default EOP)
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parents[2] / "docs" / "jira-export"
PAGE_SIZE = 100
RETRIES = 4


def _env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if not value:
        sys.exit(f"error: environment variable {name} is not set")
    return value


BASE = _env("JIRA_URL").rstrip("/")
PROJECT = _env("JIRA_PROJECT_KEY", "EOP")
def _auth_header() -> str:
    """Build the basic-auth header value from the environment credentials."""
    pair = _env("JIRA_USERNAME") + ":" + _env("JIRA_API_TOKEN")
    return "Basic " + base64.b64encode(pair.encode()).decode()


_AUTH = _auth_header()


def api(path: str, **params: object) -> dict:
    """GET a Jira REST v3 endpoint, retrying on 429 and 5xx."""
    url = f"{BASE}/rest/api/3/{path.lstrip('/')}"
    if params:
        query = {k: v for k, v in params.items() if v is not None}
        url += "?" + urllib.parse.urlencode(query)
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": _AUTH,
            "Accept": "application/json",
        },
    )
    for attempt in range(RETRIES):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            transient = error.code == 429 or error.code >= 500
            if not transient or attempt == RETRIES - 1:
                sys.exit(f"error: HTTP {error.code} for {path}: {error.read()[:400]!r}")
            delay = int(error.headers.get("Retry-After") or 2 ** (attempt + 1))
            print(f"  HTTP {error.code}, retrying in {delay}s", file=sys.stderr)
            time.sleep(delay)
        except urllib.error.URLError as error:
            if attempt == RETRIES - 1:
                sys.exit(f"error: network failure for {path}: {error.reason}")
            time.sleep(2 ** (attempt + 1))
    raise AssertionError("unreachable")


def fetch_issues() -> list[dict]:
    """Page through every issue via nextPageToken.

    Jira Cloud's /search/jql ignores startAt and reports total as -1, so opaque
    token pagination is the only correct approach here.
    """
    issues: list[dict] = []
    token: str | None = None
    page = 0
    while True:
        page += 1
        result = api(
            "search/jql",
            jql=f"project = {PROJECT} ORDER BY key ASC",
            maxResults=PAGE_SIZE,
            fields="*all",
            expand="changelog,renderedFields,names,schema",
            nextPageToken=token,
        )
        batch = result.get("issues", [])
        issues.extend(batch)
        print(f"  page {page}: {len(batch)} issues (running total {len(issues)})")
        token = result.get("nextPageToken")
        if result.get("isLast") or not token:
            break
    return issues


def fetch_all_comments(issues: list[dict]) -> dict[str, list[dict]]:
    """Fetch comments per issue, so nothing is lost to search-payload truncation."""
    comments: dict[str, list[dict]] = {}
    keys = [
        issue["key"]
        for issue in issues
        if (issue.get("fields", {}).get("comment") or {}).get("total", 0) > 0
    ]
    print(f"  {len(keys)} issues carry comments")
    for key in keys:
        collected: list[dict] = []
        start = 0
        while True:
            page = api(
                f"issue/{key}/comment", startAt=start, maxResults=PAGE_SIZE, expand="renderedBody"
            )
            collected.extend(page.get("comments", []))
            start += PAGE_SIZE
            if start >= page.get("total", 0):
                break
        comments[key] = collected
    return comments


def top_up_changelogs(issues: list[dict]) -> int:
    """Re-fetch any changelog the search response truncated at 100 entries."""
    topped = 0
    for issue in issues:
        changelog = issue.get("changelog") or {}
        total = changelog.get("total", 0)
        if len(changelog.get("histories", [])) >= total:
            continue
        histories: list[dict] = []
        start = 0
        while True:
            page = api(f"issue/{issue['key']}/changelog", startAt=start, maxResults=PAGE_SIZE)
            histories.extend(page.get("values", []))
            start += PAGE_SIZE
            if start >= page.get("total", 0):
                break
        issue["changelog"] = {"total": len(histories), "histories": histories}
        topped += 1
    return topped


def extract_links(issues: list[dict]) -> list[dict]:
    """Collapse both-ends link entries into unique directed pairs."""
    seen: set[tuple[str, str, str]] = set()
    pairs: list[dict] = []
    for issue in issues:
        for link in issue.get("fields", {}).get("issuelinks") or []:
            link_type = link.get("type", {})
            if "outwardIssue" in link:
                inward, outward = issue["key"], link["outwardIssue"]["key"]
                name = link_type.get("outward", "")
            elif "inwardIssue" in link:
                inward, outward = link["inwardIssue"]["key"], issue["key"]
                name = link_type.get("outward", "")
            else:
                continue
            identity = (inward, outward, link_type.get("name", ""))
            if identity in seen:
                continue
            seen.add(identity)
            pairs.append(
                {
                    "type": link_type.get("name", ""),
                    "outward_description": name,
                    "inward_description": link_type.get("inward", ""),
                    "from": inward,
                    "to": outward,
                    "link_id": link.get("id"),
                }
            )
    return pairs


def write_json(name: str, payload: object) -> None:
    path = OUT_DIR / name
    path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    size = path.stat().st_size
    print(f"  wrote {path.relative_to(Path.cwd())} ({size:,} bytes)")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()

    print(f"Exporting project {PROJECT} from {BASE}")

    print("issues:")
    issues = fetch_issues()
    issues.sort(key=lambda issue: int(issue["key"].split("-")[1]))

    print("changelogs:")
    topped = top_up_changelogs(issues)
    print(f"  {topped} changelogs re-fetched in full")

    print("comments:")
    comments = fetch_all_comments(issues)
    comment_count = sum(len(value) for value in comments.values())
    print(f"  {comment_count} comments collected")

    print("links:")
    links = extract_links(issues)
    print(f"  {len(links)} unique link pairs")

    print("metadata:")
    meta = {
        "export": {
            "started_at": started,
            "finished_at": datetime.now(timezone.utc).isoformat(),
            "jira_base_url": BASE,
            "project_key": PROJECT,
            "issue_count": len(issues),
            "comment_count": comment_count,
            "link_pair_count": len(links),
            "story": "EOP-166",
            "tool": "tools/jira-migration/dump_jira.py",
        },
        "project": api(f"project/{PROJECT}"),
        "statuses": api(f"project/{PROJECT}/statuses"),
        "priorities": api("priority"),
        "link_types": api("issueLinkType"),
    }

    labels: dict[str, int] = {}
    types: dict[str, int] = {}
    statuses: dict[str, int] = {}
    for issue in issues:
        fields = issue.get("fields", {})
        for label in fields.get("labels") or []:
            labels[label] = labels.get(label, 0) + 1
        types[(fields.get("issuetype") or {}).get("name", "?")] = (
            types.get((fields.get("issuetype") or {}).get("name", "?"), 0) + 1
        )
        statuses[(fields.get("status") or {}).get("name", "?")] = (
            statuses.get((fields.get("status") or {}).get("name", "?"), 0) + 1
        )
    meta["derived"] = {
        "labels": dict(sorted(labels.items(), key=lambda kv: (-kv[1], kv[0]))),
        "issue_types": dict(sorted(types.items(), key=lambda kv: -kv[1])),
        "statuses": dict(sorted(statuses.items(), key=lambda kv: -kv[1])),
        "first_key": issues[0]["key"] if issues else None,
        "last_key": issues[-1]["key"] if issues else None,
    }

    write_json("issues.json", issues)
    write_json("comments.json", comments)
    write_json("links.json", links)
    write_json("meta.json", meta)

    print(
        f"\nDone: {len(issues)} issues, {comment_count} comments, "
        f"{len(links)} link pairs, {len(labels)} labels"
    )


if __name__ == "__main__":
    main()
