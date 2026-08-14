# ADR-029: All Rule Files Are Loaded Globally via a Single Glob

**Status:** Accepted
**Date:** 2026-08-14
**Deciders:** @product-owner, @tech-lead

## Context

The `.opencode/rules/` directory contains 15 instruction snippets that govern how every
agent in this repository must behave — naming conventions, security posture, API design,
database migrations, observability, feature flags, and more. Before this decision, they
reached a model by exactly two routes:

1. **Always loaded** — four cross-cutting rules (`clean-architecture.md`, `security.md`,
   `git-commits.md`, `testing.md`) listed individually in the `instructions` array of
   `.opencode/opencode.json`.

2. **Read on demand** — the remaining eleven were named in a `## Required Reading` section
   at the end of nine agent `.md` files, with the instruction to read them with the `read`
   tool "before you start work that touches them".

This design had two compounding defects.

**Defect 1 — unsatisfiable trigger.** "Before you start work that touches them" requires
judging the relevance of a file the agent has not yet read. That judgement is unfalsifiable:
the cheap inference is always "not relevant". Nine of fifteen rules reaching no model is the
designed outcome of this instruction, not an accident.

**Defect 2 — zero-intersection routing hole.** The eleven lazy rules were routed to specific
agents, but the routing was not symmetric with the rules those agents' DoD gates enforced.
`@tech-lead` was routed only `feature-flags.md` and `versioning.md`. The union its five DoD
gates enforce is `api-design`, `build-quality`, `caching`, `error-handling`, `observability`,
and `resilience`. The intersection of those two sets is empty. `observability.md` was present
in `@code-reviewer` and `@tester-unit-and-quality` but absent from `@tech-lead`, which is
exactly the failure mode that materialised: two gates had to insist on observability
compliance during EOP-14 because the primary agent had never seen the rule. `@security-auditor`
and `@ui-builder` had no `## Required Reading` section at all.

A behavioural propagation test confirmed the mechanism: `@ui-builder` was dispatched via the
`task` tool with no tools and asked to report what was in its context. It confirmed the four
globally-loaded rules verbatim and reported `observability.md` and `api-design.md` absent.
This established that the `instructions` array does propagate into subagent sessions with full
file bodies, and that the lazy rules genuinely were absent.

The OpenCode agent schema has no per-agent `instructions` field. The only per-agent prompt
mechanisms are the markdown body and `prompt: "{file:...}"`. Eager per-agent rule loading
therefore cannot be expressed in configuration — it must be either global (via the
`instructions` array) or inlined/directed inside each agent's markdown body. The lazy
approach was the only available per-agent mechanism; the defects are intrinsic to it, not
implementation errors.

## Decision

Replace the four individually-listed paths in the `instructions` array with a single glob:

```json
"instructions": [".opencode/rules/*.md"]
```

All fifteen rule files are now loaded globally for every agent and every subagent dispatch.
The nine `## Required Reading` sections are deleted from the agent definitions. Leaving them
in place after the glob change would make them actively false — they would tell agents that
rules are absent from their context when they are present — and would teach agents to distrust
their own context.

## Consequences

**Accepted gains.**

- The unsatisfiable trigger is removed entirely. No agent needs to judge whether a rule is
  relevant before reading it; every rule is already present.
- The routing hole is closed. `@tech-lead` now sees `observability.md`, `api-design.md`,
  `build-quality.md`, `caching.md`, `error-handling.md`, and `resilience.md` on every
  dispatch, as do `@security-auditor` and `@ui-builder`.
- A new rule file placed in `.opencode/rules/` is binding on every agent the moment it is
  committed, with no per-agent list to update. The manual sync that produced the drift is
  gone.
- The directory must contain rules only, because the glob takes everything in it. This is a
  constraint, but it is also a guarantee: anything in `.opencode/rules/` is a rule.

**Accepted costs, stated plainly.**

- **~20 KB / ~5.1k tokens are always-on in every session and every subagent dispatch.**
  The four previously-loaded files totalled ~1.6 KB; the full corpus is ~20 KB. This
  knowingly contradicts the upstream OpenCode documentation's recommendation to use lazy
  loading to keep the always-on instruction budget small. The trade is accepted because
  20 KB is a bounded, known cost and the defects it removes are unbounded in their effect
  on delivery quality.
- `database.md` (63 lines) and `performance-testing.md` (104 lines) are the two largest
  files and serve primarily `@db-designer` and `@performance-engineer` respectively. They
  are now loaded for every agent. The alternative — a narrower glob or a per-agent
  mechanism — reintroduces the routing problem this decision exists to solve.
- The two files that grew most between the original design and this decision
  (`database.md` and `performance-testing.md`, edited 2026-08-10) account for ~42% of
  the corpus. The ~12 KB figure cited in the Blueprint before this change was stale; the
  actual cost is ~20 KB.

## Related

- [ADR-022](ADR-022-agent-model-tier-governance.md) — precedent for recording a decision
  about the agent governance layer as an ADR, rather than as an amendment to a living
  reference document
- [ADR-011](ADR-011-graphify-knowledge-graph.md) — precedent for recording a decision about
  the development toolchain as an ADR
- [ADR-013](ADR-013-feature-flags.md) — `feature-flags.md` is one of the eleven rules that
  was previously lazy; it is now always loaded
- `.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md` §7.6 — the superseded
  design is recorded in a blockquote there; the new design replaces the surrounding prose
- `AGENTS.md` — carries the operational statement of this rule for day-to-day work
- EOP-000 (this decision and its implementation)
