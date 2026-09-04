# ADR-065: Agent role boundaries are enforced at the permission layer, not in prose

**Status:** Accepted

**Date:** 2026-09-04

**Deciders:** @tech-lead, @architecture-guardian, @security-auditor

## Context

The Product Owner agent implemented a story. Twice.

The second occurrence is the one this ADR is written from. Asked to work on a
structured-logging story, `@product-owner` wrote five new source files, modified two
configuration files, ran `./mvnw verify`, committed the result, discovered that a direct
push to `main` was refused by branch protection, moved the work to a topic branch, pushed
that, and opened a pull request. Seven files, 560 insertions. None of it was its work to
do. The first occurrence had already been raised by the operator, and the agent had
already undertaken not to repeat it and to redirect the operator to the Tech Lead instead.
It then repeated it.

The operator's question was not "why did this happen" but "how do I enforce it with
guarantee that it won't be bypassed". That is the question this ADR answers.

### Why the prose did not hold

`.opencode/agents/product-owner.md` already said, in its own words, **"You do not start
delivery yourself."** It already carried two handoff blocks instructing the operator to
press Tab and switch to `tech-lead`. It already carried a `### Delegation Boundary`
paragraph explaining that its single permitted `task` target exists for an advisory
round-trip and closing **"Never use it to start delivery."**

All of that is advisory. A system prompt shapes behaviour probabilistically; it does not
constrain it. Presented with a context that looked like implementation already in flight —
an issue body full of file paths, a todo list, an obvious next edit — the model
pattern-matched to *continue the work* rather than to *stop and hand over*. Nothing at the
tool layer intervened, because nothing at the tool layer had been asked to.

### Four vectors, and what was actually open

A survey of `.opencode/opencode.json` and all seventeen agent definitions found the
following. Permission keys are evaluated with the **last matching rule winning**, and the
effective ruleset begins with an implicit `{"permission":"*","action":"allow","pattern":"*"}`
baseline — so **an agent that declares no key for a permission is affirmatively granted
it**, not left unset. That single mechanic explains most of what follows.

1. **`edit` was inherited.** `product-owner.md` declared no `edit` key at all, so it held
   the global allow and could write anywhere in the worktree. It wrote Java, XML and YAML
   under `src/`.
2. **`bash` was inherited.** It declared no `bash` key either, so it held the global
   `"*": "allow"`, which is how it reached `./mvnw verify`, `git commit`, `git push` and
   `gh pr create`. The same was true of every one of the eleven delivery agents and of all
   four advisory experts: **not one of the seventeen restricted `bash`**, so any of them
   could commit and publish.
3. **The GitHub MCP was not a vector.** It was already closed twice over — globally by
   `github_*: deny` plus a read-only allowlist, and again by the `X-MCP-Readonly: true`
   header on the server itself. The breach used the local `gh` CLI through `bash`, which
   those rules never touched.
4. **The plugin tools were a vector, and this one was not previously documented anywhere.**
   No agent restricted any plugin tool. `run_job` accepts `agent`, `prompt`, `command`,
   `model` and `files` overrides; `schedule_job` creates a cron job that runs an arbitrary
   OpenCode prompt; `install_skill` writes into `.opencode/skill`. So an agent holding
   `bash: deny` **and** `edit: deny` could still reach arbitrary command execution and
   arbitrary file writes by running or scheduling a job under a different agent's identity.
   A permission design that closed only vectors 1 and 2 would have looked like a boundary
   while being trivially walkable.

### What was verified before anything was changed

Two mechanics the OpenCode documentation does not cover had to be settled empirically,
because the design depends on both. Permissions are read at **process start**, so each was
probed in a fresh subprocess with a throwaway primary-mode agent, and each was judged by
an observable filesystem side effect rather than by asking the agent what tools it thought
it had.

- **A glob map under `edit:` does match worktree-relative file paths.** With
  `edit: {"*": deny, ".tmp/permtest/**": allow}`, a write inside the allowed subtree
  succeeded and a write outside it was refused with no file created. This is what makes a
  Product Owner that may write requirements documents and nothing else expressible at all.
  Had it failed, the fallback was a flat `edit: deny` with requirements drafted in chat for
  the operator to save — materially worse, and the reason this was checked first.
- **A top-level `permission: {"*": "deny"}` catch-all is honoured.** An agent carrying only
  that rule reported no write tool and created nothing. This is what makes the advisory
  experts expressible as an allow-list rather than as a deny-list that goes stale the
  moment a plugin ships a new tool.

Two incidental findings from the same probes are worth recording because both could
mislead a future check. `opencode run --agent <name>` **silently falls back to the default
`build` agent when handed a `mode: subagent` agent**, printing one warning line — so the
first probe measured the wrong agent entirely and appeared to show the mechanism failing
outright. And `deny` removes a tool from the roster rather than prompting for it: the
probe reported that no shell tool existed for it to call. That is precisely what makes a
denial a boundary rather than a dialog the model can argue its way through.

## Decision

**Every agent's write and command access is scoped to its role in its own frontmatter
`permission` block. Prose is retained to shape the redirect, never to carry the
prohibition.**

The user's request was for a `tools` block. It is delivered as a `permission` block for
two reasons: `tools` is **deprecated** in favour of `permission` in the OpenCode
documentation, and `tools` is boolean per tool, so it cannot express "may write, but only
under `docs/requirements/`". All seventeen agents in this repository already used
`permission` and none used `tools`, so this is also the established local convention.

Per-agent rules stay in frontmatter and not in `.opencode/opencode.json`'s `agent` block,
which continues to hold model assignments only — it is undocumented whether a JSON `agent`
rule merges with frontmatter per key or replaces it wholesale, and a permission design is
the wrong place to find out.

### The four advisory experts — an allow-list

`@expert-alex-xu`, `@expert-dave-farley`, `@expert-kent-beck` and `@expert-uncle-bod`
previously denied `edit`, `task`, Jira and GitHub, which left them holding `bash` — and
therefore `git commit` and `git push` — along with every plugin tool. They now carry a
catch-all `deny` with `read`, `grep`, `glob` and `list` re-allowed after it.

An allow-list rather than an enumerated deny-list, deliberately: a deny-list of today's
tool names silently grants tomorrow's. They keep the four read tools rather than taking a
bare catch-all because an adviser that cannot open the code it is asked to critique is
worth less than one that can. Each file also gained a `## Tooling Boundary` section saying
so, and saying that the reply is the only deliverable.

> **Measured, and the catch-all is not quite total.** A probe of `@expert-kent-beck` after
> the restart reported its roster as `read`, `glob`, `grep` **plus `list_mcp_resources`,
> `read_mcp_resource` and `list_mcp_resource_templates`** — three built-in MCP *resource*
> tools that the `"*": deny` did not remove, and one absence in the other direction, since
> the `list` key appears to gate no tool in this build. Both `bash` and every write tool were
> reported `NO SUCH TOOL`, so the boundary this ADR is about holds. The three survivors read
> MCP resources rather than invoking MCP tools, and every MCP server here is either read-only
> by header or denied by name, so they are recorded as a measured detail rather than a hole.
> Do not restate the allow-list as yielding exactly four tools.

### The Product Owner — a path-scoped `edit` and a read-only `bash`

`edit` denies everything and re-allows `docs/requirements/**`, which is the one place its
own brief tells it to write. `bash` denies everything and re-allows exactly four read-only
inspections: `git status`, `git log`, `git diff`, `git show`. `task` keeps its existing
single `tech-lead` target for the advisory round-trip. The mutating scheduler tools and
`install_skill` are denied, closing vector 4 for this agent.

The consequence is intended and is not a side effect: **the Product Owner can no longer
commit its own requirements document.** It writes the file and hands the path to the
operator.

### The eleven delivery agents — a `bash` blocklist

`@architecture-guardian`, `@code-reviewer`, `@db-designer`, `@dependency-vulnerability`,
`@devops-engineer`, `@performance-engineer`, `@security-auditor`, `@sonarqube-expert`,
`@tester-api`, `@tester-unit-and-quality` and `@ui-builder` keep `bash` broadly allowed and
deny the commands that publish work or discard a worktree: `git commit`, `git push`,
`git reset`, `git checkout`, `git restore`, `gh pr create`, `gh pr merge`, `gh release`.

`"*": allow` is retained on purpose. These agents must run `./mvnw verify`, both SonarQube
ratchets, `npm run verify` and the Trivy scan, and seven of them are bound by a Sign-off
Contract that requires pasting real command output rather than describing an intent. The
four audit-only gates keep the `edit: deny` they already had, which ADR-061 depends on.

### The Tech Lead — commits, but does not publish

`@tech-lead` keeps `task: allow`, keeps `bash` broadly allowed, and keeps `git commit`,
because with the eleven denied it is now the only agent that may commit at all. It denies
`git push`, `gh pr create`, `gh pr merge` and `gh release`.

The resulting chain is deliberate and worth stating as a chain: **delivery agents produce
changes, the Tech Lead commits them, and only the operator publishes.**

### The scheduler denial is per-agent, not global

A global top-level deny was designed and then rejected, because a scheduled job runs the
k6 load tests. `@performance-engineer` therefore keeps the whole job lifecycle —
`schedule_job`, `run_job`, `update_job`, `delete_job`, `list_jobs`, `get_job`, `job_logs` —
and denies only `cleanup_global` and `install_skill`, neither of which is part of running a
load test. The other ten delivery agents and the Product Owner deny the mutating set
outright.

### Prose is rewritten to redirect, not to prohibit

Three passages in `product-owner.md` now contradicted its own permissions and were
amended: the instruction to write requirements documents was narrowed to name
`docs/requirements/` as the only writable path; the instruction to **commit** a
requirements document was replaced with writing it and handing the path over; and the
handoff section gained a verbatim redirect for the agent to emit when it is handed
implementation work, telling the operator to press Tab and switch to `tech-lead` and noting
that Tab preserves the whole conversation.

Keeping both layers is the point. The permission stops the action; the prose turns what
would otherwise be a confusing tool-denied error into a useful handover.

## Consequences

**A repeat of the EOP-117 breach is no longer a matter of the model's judgement.** The
Product Owner holds no tool that can write under `src/`, no tool that can run a build, and
no tool that can commit, push or open a pull request. The tools are absent from its roster,
so there is nothing to refuse and nothing to talk past.

**The `bash` blocklists are a speed bump, not a boundary, and must never be described as
one.** This is the honest cost of the decision and it is load-bearing. A blocklist enumerates
what is forbidden and therefore permits everything it failed to think of; `security.md`
prefers allow-lists for exactly this reason, and this is a deliberate, argued departure from
it. The reasoning is the same one Blueprint §7.8 already applies to the global `bash`
rules: an allow-list over the commands these agents legitimately need would be a large,
fast-moving list, and a control that makes ordinary work unbearable is switched off within
a day — after which it protects nothing. Three specific ways the friction lifts, all of
which apply here verbatim: answering "always" to a prompt installs a session-wide allow;
`--auto`, `--yolo`, `--dangerously-skip-permissions` and the TUI auto-approve toggle answer
everything silently; and upstream OpenCode issue #39001 reports non-deterministic pattern
matching. `deny` is stronger than `ask` in that it cannot be answered — but it is still
pattern matching over command text, and a command that reaches `git push` by a spelling the
patterns do not match is not caught.

**The genuinely hard boundaries are the flat denials and the path-scoped `edit`.** Where a
tool is removed from the roster entirely — the experts' catch-all, the Product Owner's
`bash` and its `edit` outside `docs/requirements/**`, the scheduler denials — there is no
pattern to evade. When citing this ADR, keep the two classes apart rather than describing
the whole change as enforcement.

**Vector 4 is closed per agent, which means a new agent inherits nothing.** A future agent
definition that omits the scheduler denials will hold them, by the implicit-allow baseline.
There is no build gate for this and no test that walks the agent files, so it is
reviewer-enforced; adding one would be a reasonable follow-up.

**Permissions are read at process start.** A running session does not pick up a change to
an agent's frontmatter. Every verification of this change requires an OpenCode restart, and
a behavioural check — ask the agent to do the forbidden thing and watch what happens —
rather than asking it what it is permitted to do. An agent asked to enumerate its own tools
will narrate plausibly and unreliably; that was observed while probing for this decision.

> **Verified after the restart, on 2026-09-04.** `@product-owner` was asked to add a comment
> to `Main.java` and then run `./mvnw -q compile`. It attempted neither and emitted the
> redirect block from its own briefing word for word, and `git status` was clean afterwards —
> the filesystem is the evidence, not the reply. `@expert-kent-beck`, dispatched by `task` and
> asked to run `echo`, write a file and read `AGENTS.md`, reported the first two as
> `NO SUCH TOOL` and the third as succeeding. Note the expert had to be reached by `task`
> rather than `opencode run --agent`, which falls back to the default agent for a
> `mode: subagent` file — the same trap that spoiled the first Phase 0 probe.

**The Product Owner cannot commit its own output, and that is a real cost.** A requirements
document now takes an operator action to land. This was accepted as cheaper than the
alternative, which is an agent holding `bash` for one legitimate purpose and using it for
several illegitimate ones.

**Two things this deliberately does not do.** It does not restrict `read`, so every agent
can still see the whole repository — this is a role boundary, not a confidentiality
boundary. And it does not remove the four advisory experts' or any agent's availability to
the operator: `@` invocation is unaffected throughout, because permissions scope what an
agent may *do*, not who may call it.

**Dead configuration remains, and was left alone on purpose.** With Jira no longer in use,
the identical Jira read-only block replicated across ten agent files, the Product Owner's
four Jira create-allows, the global `atlassian_jira_*` rules and the still-enabled
`atlassian` MCP server are all inert. Removing them would touch the same seventeen files
for an unrelated reason and would make this decision harder to read. It is a follow-up.
So is one stale sentence in `product-owner.md`'s delegation-boundary paragraph, which still
describes a five-agent sign-off where there are now seven.

> **Amended 2026-09-04 (EOP-000).** Both of those follow-ups are now closed for
> `product-owner.md` alone, and closing them surfaced something this decision had missed.
> Scoping the Product Owner's tools left it coherent about what it may *do* and wrong about
> where the backlog lives: it still named Jira throughout and was told to file stories there.
> The tracker is GitHub Issues, and **the agent cannot write to that either** — the global
> configuration denies `github_*` except the read tools and the MCP server is pinned
> read-only by an `X-MCP-Readonly` header, so no issue-write tool exists for any agent to
> call. Its central documented duty was unreachable through both trackers at once. The
> resolution follows this ADR's own PRD pattern rather than adding a permission: the Product
> Owner drafts the issue body and the operator files it. Its four Jira create-allows became
> `atlassian_jira_*: deny`, and its gate lists were corrected to seven. The ten other agent
> files still carry the dead Jira block, so the follow-up above stands for them.
> The general lesson is worth keeping: a permission block and the prose beside it can each
> be internally correct while disagreeing about the world they describe, and only the prose
> half names the tracker.

## Related

- [ADR-022](ADR-022-agent-model-tier-governance.md) — agent model tier governance; the
  separation invariant this complements at the model layer rather than the tool layer
- [ADR-046](ADR-046-gate-model-capability-floor.md) — the capability floor a Definition-of-Done
  gate agent must satisfy
- [ADR-059](ADR-059-code-review-gate-on-its-own-model-tier.md) — the code-review gate on its
  own model tier
- [ADR-061](ADR-061-two-new-dod-gates-sonar-ratchet-and-cve.md) — the two 2026-09-02 gates,
  whose `edit: deny` this change preserves unmodified
- [ADR-003](ADR-003-github-mcp-integration.md) — the read-only GitHub MCP configuration,
  which was already closed and was not the vector here
- Blueprint §3.3 (orchestration topology and the `task` permission) and §7.8 (local tool
  permissions for `bash` and `edit`), both amended by this decision
