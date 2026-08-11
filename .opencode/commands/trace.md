---
description: Show how a story was actually delivered — which agents ran, on which models, and whether the Tech Lead pipeline and the Separation Invariant held.
---

1. Run `tools/agent-trace.py --last` from the repository root. Pass a session id or id prefix instead of `--last` if the user named a specific session, or no argument at all if the user asked which sessions exist.
2. Show the user the Mermaid dispatch tree and the "Who did what" table as returned, without rewriting them.
3. Read the "Pipeline conformance" block and say plainly whether the delivery conformed. Do not soften it. Specifically:
   - A `MISS` on stage 2 means the work was never independently reviewed, whatever the commit history claims.
   - A `RISK` line naming the same model as both author and auditor means the review was self-review and its clean findings carry little weight — the Separation Invariant in `.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md` exists precisely to prevent this.
   - A `RISK` line saying a read-only auditor made edits means an agent exceeded its permissions and that is a configuration defect worth fixing immediately.
   - An `INFO` line saying a gate made edits is **not** a defect on its own — three of the five gates may legitimately write (the testers write tests, `@architecture-guardian` writes ADRs). It means that gate's verdict attaches to a tree it changed, so check the brief actually authorised the write and that a *different* gate also approved the final tree.
   - A `PART` line ending `(unattributable: …)` is **not** a claim that anything is missing. The trace records that an agent was dispatched but never which stage the dispatch served, and `@architecture-guardian` legitimately belongs to two stages — it authors ADRs at stage 1 and gates the PR at stage 2. One dispatch is therefore credited to both, so a full roster whose only evidence for this stage is that agent cannot be reported as `OK`. Stages 1 and 2 will consequently never read `OK` while `@architecture-guardian` is in both rosters; that is deliberate under-claiming, not a failure. To settle it, check the gate verdicts themselves rather than this line — the tool cannot, and does not pretend to.
4. If anything failed, propose the concrete remedy — usually re-dispatching the missing stage-2 agents, or routing authoring work to the `MODEL_C` agents (`db-designer`, `devops-engineer`, `performance-engineer`) rather than writing it in the primary session.
