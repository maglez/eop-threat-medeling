# ADR-010: Continuous Flow over Sprint Timeboxes

- **Status:** Accepted
- **Date:** 2026-07-28
- **Author:** Engineering Team
- **Deciders:** Tech Lead, Product Owner, Architecture Guardian

## Context

The Jira project was created from Jira's Scrum template. It carries a board named "SCRUM board", a never-started sprint ("SCRUM Sprint 1", state `future`, no start or end date), and story-point estimation configured on `customfield_10016`.

None of it has ever been used. At the time of this decision the project holds **zero issues** and the sprint has never been started, so the cost of changing process model is effectively nil — and will only grow.

Meanwhile the engineering documentation commits the project unambiguously to continuous flow:

- Blueprint §2.2 mandates **Trunk-Based Development over GitFlow** — short-lived branches, small frequent pull requests, long-lived branches "strictly prohibited"
- Blueprint §2.3 mandates **Continuous Deployment** — every passing commit merged to `main` deploys to production
- Blueprint §2.4 mandates feature flags defaulting to `OFF`, explicitly so that *incomplete* work can still deploy continuously
- `tech-lead` enforces "Continuous Deployment on every commit"
- `devops-engineer` runs pipelines on every push or merge to `main` with immediate zero-downtime deploy

A sprint timebox is a batching device. Running one on top of deploy-per-commit is a direct contradiction: the pipeline releases continuously while the tracker pretends work arrives in fortnightly parcels. The contradiction had not yet caused harm only because the tracker was empty.

There is a second, deeper reason. Scrum's ceremonies are a coordination protocol designed for teammates who are **opaque** (progress is invisible until spoken aloud), **fatigue-prone**, **expensive to interrupt**, and **continuous across days**. Agents are the inverse: their state is fully inspectable from the transcript, tool calls and `git log`; they do not tire; they are free to interrupt; and they retain nothing at all between sessions. Most of the protocol addresses constraints that no longer exist.

## Options Considered

| Option | Fit with §2.2–2.4 | Cost now | Notes |
|---|---|---|---|
| **Continuous flow (Kanban-style board, WIP limit)** | Consistent | Zero — 0 issues, sprint never started | Chosen |
| Keep Scrum | Contradicts deploy-per-commit | Zero now, rising with every issue added | Would retain ceremonies whose purpose does not apply |
| Document the tension, defer the decision | Contradiction persists | Grows | Defers a decision whose cost is lowest today |

Keeping Scrum had one genuine argument in its favour: this repository sits under a `Learning/` path, so practising Scrum could carry training value independent of delivery efficiency. That argument was considered and declined — the project's own documents already teach trunk-based continuous delivery, and teaching two contradictory models at once teaches neither.

## Decision

Adopt **continuous flow**. Specifically:

1. **Sprints are disabled** on the Jira board. The board runs as a continuous-flow board: `To Do` → `In Progress` → `Done`, with work pulled as capacity frees up.
2. **A work-in-progress limit is set on `In Progress`.** The limit is a requirement of this decision; the *number* is deliberately not recorded here, because it must be tuned in the Jira UI and a number in a decision record becomes stale documentation. It is sized to **reviewer capacity, not agent throughput** — see below.
3. **Cycle time replaces velocity** as the flow metric: elapsed time from `In Progress` to `Done`. Story-point estimation is left configured on the board but unused; an unused field costs nothing, and removing it is a configuration write that is not cheap to reverse.
4. **The Definition of Done is strengthened and made machine-checkable** — see below.
5. **Retrospectives become event-driven, not periodic** — see below.

Prioritisation, an ordered backlog, INVEST-shaped stories and the walking-skeleton-first rule (§2.1) all survive unchanged. What is removed is the timebox, not the ordering.

### Why the WIP limit is sized to reviewer capacity

Agent throughput is nearly free. The scarce resource is the user's review and merge capacity — and this is now *structurally* enforced rather than merely true in practice: `main` is branch-protected with `enforce_admins: true`, so every change reaches production through a pull request that only the user can merge.

A WIP limit constrains the bottleneck directly. Velocity cannot, because it measures agent output — the side of the system that is no longer constrained. Ten stories in flight against one reviewer produces ten stale branches, not ten deployments.

### Why the Definition of Done must get stronger

Removing the sprint boundary removes the last implicit "is this actually done?" checkpoint. The DoD becomes the **only** completion signal, so it must be verifiable without trusting an agent's self-report.

This is not hypothetical. During the Jira integration smoke test, the Product Owner reported that its ticket description had been corrupted — checkboxes flattened to bullets, Gherkin fences degraded and syntax highlighting lost. Inspection of the stored ADF disproved every part of it: 11 real interactive `taskItem` nodes and 4 `codeBlock` nodes with `language="gherkin"` were present. The agent had been reading the MCP client's lossy echo rather than what Jira persisted, and reported with confidence.

Therefore each DoD criterion must be expressed as something a command can decide:

- Passing test named and runnable (`./mvnw test`), not "tests written"
- CI `build` check green on the pull request, not "CI should pass"
- Feature flag present and defaulting to `OFF`, verifiable by reading the config
- Endpoint responding as specified, verifiable by a request
- Documentation updated in a named file, verifiable by `git diff`

An agent's assertion that work is complete is a **claim**. The DoD is the evidence.

### Why retrospectives invert rather than transfer

A human retrospective is fortnightly because humans resist pausing to reflect under delivery pressure, and their lessons accrete slowly but *persist*. Agents are the opposite: they carry **zero** learning between sessions, and the next session boots from `AGENTS.md`, this project's blueprint and these ADRs.

A lesson left in a transcript is therefore a lesson destroyed at session end. Retrospection must be **triggered by an event** — a defect escaping to `main`, a false completion report, a genuine surprise — and its output must be written *immediately* into a durable file. A fortnightly meeting would be worse than useless: it would collect lessons that had already evaporated.

## Consequences

### Positive

- The tracker stops contradicting §2.2–2.4; one delivery model, documented once
- Work in progress is bounded by the constraint that actually binds — reviewer attention
- Cycle time is measurable from the board without estimation ceremony
- Lessons land in files the next session reads, instead of in meeting notes
- Migration cost is zero today: no issues to re-parent, no sprint to close

### Negative

- **Loss of cadence.** Scrum's rhythm also served the stakeholder: it guaranteed that work was shown and reflected upon at a known interval. Dropping every timebox risks "continuous" quietly becoming "never".
- **The retrospective now depends on the user.** Nothing in the agent configuration triggers it; if a defect escapes and no durable write happens, the lesson is lost at session end.
- **No forecast artefact.** Without velocity there is no ready answer to "when will this be done?" until enough cycle-time history accumulates.
- Anyone arriving with Scrum expectations will find no sprint, no planning and no standup, and needs this ADR to understand why.

### Mitigations

- The cadence risk and its event-driven mitigation are documented in Blueprint §2.5, stated as a risk rather than assumed away
- Cycle time accumulates automatically from board transitions, so forecasting improves without added ceremony
- The WIP limit is tunable in the Jira UI without amending this ADR, by design

## Implementation

Board and project configuration are **user actions in the Jira UI**. The bot account deliberately lacks `ADMINISTER_PROJECTS` (Blueprint §7.2), so no agent can perform them:

1. Project settings → Features → turn **Sprints** off
2. Delete the never-started sprint ("SCRUM Sprint 1")
3. Set a WIP limit (column maximum) on **In Progress**; all three columns currently have no limit
4. Leave board estimation on `customfield_10016` as-is

Documentation changes shipped with this ADR: Blueprint §2.5 and its Table of Contents entry, and removal of sprint-relative wording from `product-owner` and the CI/CD pipeline document.

## Related

- [Blueprint §2.5 Continuous Flow over Timeboxes](../../.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md#25-continuous-flow-over-timeboxes)
- [ADR-003: GitHub MCP Integration](ADR-003-github-mcp-integration.md) — branch protection context for the reviewer bottleneck
- [ADR-006: Build Quality Gates](ADR-006-build-quality-gates.md) — the automated checks a machine-checkable DoD relies on
