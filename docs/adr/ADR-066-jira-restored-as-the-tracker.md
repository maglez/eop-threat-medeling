# ADR-066: Jira is restored as the tracker, and the two keyspaces are reconciled with a permanent offset

**Status:** Accepted

**Date:** 2026-09-05

**Deciders:** @tech-lead, the operator

## Context

On 2026-08-26 the `EOP` project in Jira Cloud (`https://maglez.atlassian.net`) was expected to become inaccessible: the Free plan was believed to be lapsing, and EOP-166 exported all 163 issues then in the project — `EOP-3` through `EOP-165` — into GitHub Issues on `maglez/eop-threat-modeling`. The frozen dump remains at `docs/jira-export/`, the one-way tooling that produced it remains at `tools/jira-migration/`, and ADR-065's 2026-09-04 amendment recorded the consequence in prose and in permissions: the Product Owner's tracker became GitHub Issues, and because the GitHub MCP server is pinned read-only, that agent was reduced to *drafting* issue bodies for the operator to file. Its `atlassian_jira_*` permission was set to `deny` outright, on the reasoning that a re-enabled MCP server must not quietly reopen a write path to a dead tracker.

The premise turned out to be false. The Jira site was still live on 2026-09-05, and access was restored in a single step once the credential was correct. Two facts had to be established before anything could be decided.

**Authentication.** `mcp-atlassian` sends HTTP Basic auth to `JIRA_URL`. A **granular** (scoped) Atlassian API token is an OAuth bearer credential honoured only at `https://api.atlassian.com/ex/jira/{cloudId}/…`, and when it is presented as a Basic password against the site URL it is **silently ignored rather than rejected** — every request then executes as an anonymous user. That failure mode is genuinely deceiving: `GET /rest/api/3/project/search` returns `200` with `total: 0`, which reads as an empty backlog rather than as an authentication failure. The same token against the gateway with `Authorization: Bearer` returned `total: 1` and listed the project. No combination of environment variables makes a scoped token work with this MCP server, because presenting a bearer token against the site URL — what the Server/DC `JIRA_PERSONAL_TOKEN` mode would do — returns `403`.

**Divergence.** Both trackers had been written to after the migration. Jira had grown to `EOP-182`: seventeen issues, `EOP-166` through `EOP-182`, created between 2026-08-23 and 2026-08-26, of which none was Done. GitHub had grown to `EOP-193`, with most of its issues closed and its most recent activity on 2026-09-04. The two sequences therefore denote different work over the same numbers, and `main`'s commit history already contains both readings: `[EOP-167]` labels the `erDiagram` build gate, `[EOP-168]` the proactive follow-suit hint, and `[EOP-182]` a CIDR mask test — none of which is what the Jira ticket of that number describes. Nine of the seventeen Jira tickets described work that had in fact shipped, under ADRs ADR-059 through ADR-063, while sitting in `To Do`.

That last point is what forced a decision rather than a configuration change. `.opencode/rules/git-commits.md` mandates an `[EOP-NNN]` prefix on every commit. Granting the Product Owner Jira write access without reconciling the sequences would have had it allocating from `EOP-183` while the repository was already citing `EOP-193`, so the mandated prefix would have stopped resolving to one thing — a worse position than the status quo, in which the agent could not file at all.

## Decision

**Jira is the tracker again.** The `EOP` project is authoritative for all new work. GitHub Issues is retained as a readable historical record and is written by nobody: the `github` MCP server stays remote and read-only, and no agent holds a GitHub write tool.

**Only a classic, unscoped Atlassian API token is supported.** A granular token is not a degraded configuration to be worked around; it authenticates as nobody and reads as an empty project. This is recorded here because the symptom does not resemble the cause.

**The seventeen-issue Jira tail was adjudicated against `main` before anything was created.** Each ticket's intent was checked against the commit history and the tree, and seven were closed with an evidence comment naming the delivering commits: `EOP-166`, `EOP-169`, `EOP-175`, `EOP-179`, `EOP-180`, `EOP-181`, and `EOP-178` — the last as *superseded* rather than delivered, because the approach it specified was withdrawn mid-delivery and replaced by `EOP-179`. Ten remained genuinely open.

**GitHub's post-migration issues are mirrored into Jira one-for-one, in ascending GitHub issue-number order, accepting a key offset.** Fifteen GitHub issues were created after the migration; they occupy Jira `EOP-183` through `EOP-197`. Jira allocates keys sequentially and cannot be told to start at a chosen number, so mirroring in order is also the mechanism that advances Jira's sequence past GitHub's high-water mark. Each mirrored issue's description carries the GitHub issue as a link, the key that issue held in the GitHub keyspace, its state at the time of mirroring, and the commit that delivered it. Thirteen were closed to match; two — mirroring GitHub `#329` and `#332` — remain open because they are still open there.

**Only the three keys that are genuinely ambiguous in `main` were renumbered.** `EOP-167`, `EOP-168` and `EOP-182` were re-filed unchanged as `EOP-198`, `EOP-199` and `EOP-200`, each new issue carrying a provenance paragraph and a `Duplicate` link back to the original, and each original closed with a comment naming its replacement. `EOP-200` retains the parent Epic `EOP-180`. The other seven carried-forward tickets — `EOP-170`, `EOP-171`, `EOP-172`, `EOP-173`, `EOP-174`, `EOP-176`, `EOP-177` — keep their keys and their statuses, because those numbers appear nowhere in `main` and so mean one thing only. Renumbering them would have destroyed ticket identity, including an in-progress state and an Epic parentage, to fix an ambiguity that did not exist.

**The Product Owner regains issue writes, and only issue writes.** Its permission block allows `atlassian_jira_*` and then denies, individually, every tool that would let it act on delivery rather than on requirements: `transition_issue`, `assign_issue`, `add_worklog`, the sprint and version tools, `delete_issue`, `remove_*`, and `move_*`. The `move_*` deny is repeated at agent level because a per-agent permission block replaces the top-level one rather than merging with it. ADR-065's role boundary therefore survives intact; what changes is that filing and refining a ticket is now recognised as requirements work, which that agent may do, rather than tracker mutation, which it may not.

## Consequences

**The offset is permanent, and it is the price of the decision.** Jira `EOP-184` mirrors the GitHub issue that carried the key `EOP-183`; the shift persists across the whole mirrored band. A bare `EOP-NNN` in the range 182–193 is therefore ambiguous on its own, and the resolving evidence is the mirrored issue's description, which names its GitHub origin explicitly. This was chosen over collapsing the duplicated keys onto single Jira issues, which would have aligned the numbers by merging two distinct pieces of work, and over abandoning alignment entirely, which would have left the ambiguity live in the range where new work is filed rather than confined to a closed historical band.

**GitHub's own keyspace is not clean either, which is part of why alignment was unreachable.** `EOP-187`, `EOP-188` and `EOP-193` were each used for two different GitHub issues, and one GitHub issue in the band carries no key at all. Fifteen issues over eleven keys cannot be made to correspond one-to-one by any renumbering.

**`main` is not rewritten.** Within the repository's history, `[EOP-167]`, `[EOP-168]` and `[EOP-182]` continue to label the GitHub-keyspace work, and the Jira issues of those numbers are closed as superseded. A reader tracing one of those three prefixes must expect the commit and the closed Jira ticket to disagree, and should follow the `Duplicate` link.

**Nothing in the build enforces any of this.** There is no gate comparing Jira against the repository, and none is proposed: the tracker is a live external system, so a build test over it would be neither hermetic nor meaningful. The reconciliation is a one-off correction recorded in prose, and the only standing protection against a recurrence is that a single tracker is now authoritative.

**The reconciliation is not exhaustive at the GitHub end.** Four issues imported from the original migration remain open in GitHub although their Jira counterparts are closed. They were left alone deliberately: GitHub is now history, and editing a historical record to agree with a live one inverts which of the two is authoritative.

**Two Jira-specific authoring hazards were found and are now documented in the Product Owner's prompt.** Jira autolinks any `KEY-NNN`-shaped token, so a bare `ADR-066` in an issue body becomes a link to a nonexistent Jira issue — every such token must be written in backticks. And the Markdown-to-wiki conversion applied on the way in stores an unpaired `**` literally, so emphasis must be correctly paired or omitted. Both defects were introduced and then corrected during this reconciliation; a residual cosmetic artefact of the second remains in the mirrored issues' bullet labels and was judged not worth fifteen corrective edits.

**One prior follow-up is closed as unnecessary rather than done.** ADR-065's 2026-09-04 amendment proposed deleting the read-only Jira permission blocks carried by ten other agents as dead configuration. They are live again, and correct as they stand: those agents read the tracker and must not write to it.

## Alternatives considered

**Keep GitHub Issues as the tracker and grant the Product Owner read-only Jira.** Rejected by the operator. It would have preserved the status quo in which no agent can file a ticket, leaving the Product Owner's central deliverable a body of text for a human to paste.

**Migrate GitHub's post-migration issues back wholesale, bodies and comments included.** Rejected on cost against benefit. Every mirrored issue is closed work whose full detail is one link away, and copying it would have duplicated a record rather than referenced it — the same objection that makes GitHub, not Jira, the historical authority for that period.

**Collapse the duplicated GitHub keys so that Jira's numbers align exactly.** Rejected because it destroys information: two distinct pieces of shipped work, each with its own commit in `main`, would have been recorded as one issue.

**Renumber all ten carried-forward tickets rather than three.** Rejected once the commit history was actually searched. Seven of the ten keys appear nowhere in `main`, so renumbering them would have cost ticket identity — an in-progress state, an Epic parentage, and every existing reference — for no gain in clarity.

## Related

- [ADR-065](ADR-065-agent-role-boundaries-at-the-permission-layer.md) — agent role boundaries at the permission layer, whose 2026-09-04 amendment this decision reverses
- [ADR-022](ADR-022-agent-model-tier-governance.md) — agent model tier governance, the other place where an agent's configuration is treated as a reviewed architectural fact rather than a setting
