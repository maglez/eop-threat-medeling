# ADR-058: `@code-reviewer` Moves To `MODEL_F`, Closing The Last Separation-Invariant Exception

**Status:** Accepted (2026-08-24)
**Date:** 2026-08-24
**Deciders:** operator, primary agent (`MODEL_A`)

## Context

[ADR-022](ADR-022-agent-model-tier-governance.md) states the Separation Invariant — a review gate
must not share weights with an agent that writes the artefact it reviews — and states it
*conditionally*, with documented exceptions. [ADR-046](ADR-046-gate-model-capability-floor.md)
closed the second of those two on 2026-08-21 by moving `@architecture-guardian` and
`@security-auditor` to `MODEL_F`. One exception survived: **test code**.

That surviving exception is the strongest form of the defect the invariant exists to prevent. On the
Bedrock mapping in force, `@code-reviewer`, `@tester-api` and `@tester-unit-and-quality` all resolve
to `amazon-bedrock/eu.anthropic.claude-sonnet-4-6` — not merely the same family but the **same model
identifier**. `@code-reviewer` judging a tester-authored test is identical weights judging identical
weights, so neither degree of independence ADR-022 defines applies: not family-independent, and not
even the weaker model-independent.

ADR-046 improved the position without closing it. Test artefacts gained a family-independent
reviewer there for the first time, because the two gates it moved to `MODEL_F` also review test
code. So test code was never *unreviewed* across a family boundary, and this ADR must not be read as
claiming it was. What remained is narrower and worse targeted: the one gate whose entire remit *is*
code structure, SOLID compliance and static defects — the gate a reasonable reader would name first
if asked who reviews a test — supplied zero independence over precisely the artefact class it is
most specifically responsible for. Independence over test code came only from two gates reviewing it
incidentally, from an architecture and a security perspective respectively.

The user-facing consequence is what forced the question. The project's Executive Summary claimed
five independent review gates "deliberately assigned to a different AI model family from the one
that wrote the code", and then had to spend a paragraph retracting the claim for test code. Honest
documentation of a weakness is better than concealing it, but it is not as good as not having the
weakness.

`MODEL_F` is the obvious destination. Its identifier, `amazon-bedrock/minimax.minimax-m2.5`, is
outside Anthropic Claude (`MODEL_A`, `MODEL_B`, `MODEL_D`) and outside Qwen (`MODEL_C`, `MODEL_E`),
so it is family-independent of every tier that authors anything in this repository. ADR-046's
clauses 1 and 2 — the capability floor — were established for that exact identifier on 2026-08-21
and are unaffected by which gate sits on it. Two things did need establishing before the move:
clause 3's family requirement against `@code-reviewer`'s *own* artefact classes, and clause 4's
probe, which binds every gate pin and had only ever been run for architecture and security audit
work.

## Decision

1. **`@code-reviewer` moves from `MODEL_B` to `MODEL_F`** in `.opencode/opencode.json`. `MODEL_F`
   now holds three gates: `@architecture-guardian`, `@code-reviewer` and `@security-auditor`. The
   five Definition-of-Done gates are split two on `MODEL_B` (the two testers) and three on
   `MODEL_F`. No new tier variable is created — the count stays at six variables and five distinct
   models, so ADR-046's accepted cost 7 is not re-incurred.

2. **`MODEL_F`'s stated purpose broadens.** ADR-046 clause 5 created it as the tier for "the two
   gates that review `MODEL_A`'s own output". That description no longer covers its occupants:
   `@code-reviewer` reviews `MODEL_B`-authored test code as well as `MODEL_A`- and
   `MODEL_C`/`MODEL_E`-authored production code. `MODEL_F` is henceforth **the audit tier for gates
   that must be family-independent of every authoring tier**, and it must be pointed only at a model
   family that no `MODEL_A`–`MODEL_E` variable shares.

3. **Clause 3 is satisfied against both of `@code-reviewer`'s artefact classes.** ADR-046 clause 3
   requires family-independence against each class a gate reviews where it reviews more than one.
   `@code-reviewer` reviews production code (authored on `MODEL_A`, `MODEL_C` and `MODEL_E`) and
   test code (authored on `MODEL_B`). MiniMax is a different family from Anthropic Claude and from
   Qwen, so the requirement holds against both.

4. **Clause 4 is satisfied by a probe specific to this gate role**, run 2026-08-24 against
   `amazon-bedrock/minimax.minimax-m2.5` with a control run against the incumbent
   `amazon-bedrock/eu.anthropic.claude-sonnet-4-6`, and recorded in Blueprint §3.4.1. Stage 1
   established invocability and truthful tool use against independently-verified ground truth; stage
   2 exercised gate work under the Sign-off Contract — quote the relevant line verbatim, give its
   line number, state whether a named defect is present, and terminate with a bare `VERDICT:` line.
   The target was chosen so that the plausible finding is the wrong one, and the candidate did not
   take the bait. Both stages passed on both models. The existing 2026-08-21 verdict was not
   treated as transferable: it screened for architecture and security audit work, and a gate pin
   without a recorded passing verdict for the work it will do is non-compliant under clause 4.

5. **The Separation Invariant is now unconditional.** It has **zero** exceptions, and every citation
   of it must say so. Text stating "one documented exception" or "two documented exceptions" is
   stale from this date, wherever it appears.

## Consequences

### Accepted gains

**The invariant stops being conditional.** Every one of the five gates is now family-independent of
every tier that authored the artefact it reviews, on every path. The qualification the Executive
Summary carried is deleted rather than reworded, because there is no longer anything to qualify.

**Test code gains the reviewer that was actually missing.** Independence over test artefacts no
longer rests on two gates looking at them from an architecture and a security angle. The gate whose
remit is code structure now sits outside the testers' family, so a systematic blind spot in
Claude-authored test code — a wrong assertion that looks right, a test that cannot fail, a mock that
asserts on itself — is now judged by weights that do not share the blind spot.

**`@code-reviewer` gains output headroom and costs less.** `MODEL_F`'s ceiling is 98,304 output
tokens against `MODEL_B`'s 64,000, comfortably clear of ADR-046's 40,000 floor, and `MODEL_F` is the
cheapest tier in use at 0.3/1.2 per million tokens. A gate that must enumerate severity-tagged
findings with `file:line` evidence benefits from the larger ceiling more than most.

**No new tier.** The alternative of a seventh variable was rejected: `@code-reviewer`'s requirements
under clauses 1 through 3 are identical to the two gates already on `MODEL_F`, and every additional
variable multiplies the number of documented tables that can drift out of step with the config.

### Accepted costs

**The same-model-ID overlap is relocated, not eliminated — and this ADR must never be cited as
though it were.** `@architecture-guardian` authors this project's ADRs and C4 models, and it runs on
`MODEL_F`. `@code-reviewer` and `@security-auditor` now review that prose on their author's exact
model identifier. That is the same shape of defect this ADR closes for test code, moved to a
different artefact class. It is not new — ADR-046's accepted cost 3 already put `@security-auditor`
in that position on 2026-08-21 — but this change enlarges the cluster from two members to three, and
that enlargement is a cost of this decision rather than an inherited one.

The trade was made deliberately and on three grounds. Documentation prose does not execute, does not
ship to users and cannot carry a vulnerability, whereas test code is the executable evidence the
whole five-gate arrangement rests on: a defective test silently withdraws a guarantee. Prose is
nonetheless partly machine-checked here — `AdrIndexConsistencyTest`, `DeckArithmeticClaimsTest`,
`MermaidSequenceTextTest` and the other documentation-integrity gates read repository Markdown as
text and fail the build — so a badly-authored ADR turns the build red rather than passing quietly,
which is a backstop test code does not have from any comparable mechanism. And the overlap bites on
a narrower surface than the one it replaces, because `@code-reviewer`'s remit is source code and
SOLID compliance rather than architectural narrative; it is not the natural first reviewer of an ADR
in the way it was the natural first reviewer of a test.

This is a real residual weakness and it stays documented as one. Closing it would need a further
tier for `@architecture-guardian`'s authoring role, or a gate outside both Anthropic Claude and
MiniMax, and neither is justified by the evidence available today.

**MiniMax now carries three of the five gates.** ADR-046's accepted cost 2 recorded that
`minimax.minimax-m2.5` had passed one probe and had no track record at gate work here. It has now
passed a second probe, in a second role, but a probe remains a snapshot. The blast radius of a
regression in its Sign-off Contract behaviour grows from two gates in five to three in five, which
is a majority of the gate set. The mitigation is unchanged and is ordinary observation: a `VERDICT:`
line returned without the contracted evidence is a regression in the pin, not a finding to be
excused, and it must be answered by reconsidering the pin.

**`MODEL_F` is still not EU-pinned, and a third gate's traffic now runs on it.** Only the
`eu.`-prefixed Anthropic identifiers carry an EU inference profile; `MODEL_F` is a bare identifier
like `MODEL_C` and `MODEL_E`. ADR-046 recorded this as unverified rather than resolved and noted it
adds no residency posture the repository did not already have. That remains true, and it now applies
to code review as well as to architecture and security audit.

**The Zen provider route is still unprobed for `MODEL_F`, and now for a third gate.** `.env.example`
carries `#MODEL_F=opencode/minimax-m2.5` in the Zen block only because `switch-provider.sh` aborts
when a variable is absent from the block it switches to. `./tools/switch-provider.sh zen` must be
preceded by a probe of that identifier, and the consequence of skipping it is now larger.

**Nothing enforces any of this programmatically.** ADR-046's accepted cost 1 stands unchanged: no
test asserts that a gate's tier is reasoning-capable, that its probe verdict is recorded, or that
its family differs from its authors'. `tools/agent-trace.py` detects same-model self-review after
the fact and nothing else. The wording of this ADR is the mechanism, and review is the enforcement.

**`/trace`'s existing self-review finding for test code becomes a false positive, and a new true
positive appears.** ADR-022 instructed that a `RISK … self-review` line for the
`@code-reviewer`/tester overlap was a true positive and must not be silenced. From this date such a
line would instead signal a stale pin or a stale tier table and should be investigated as a
regression. The general instruction survives in a new place: an overlap reported between
`@architecture-guardian`'s authored documentation and its `MODEL_F` reviewers is genuine, and must
not be silenced either.

**This pin is not in force for the session that made it.** Configuration is read at session start,
so `@code-reviewer` reviewing this very change still runs on `MODEL_B`. That review is therefore the
last one this repository performs under the exception being closed, and any claim that this story
exercises the new pin live is false. The pin takes effect at the next OpenCode restart.

**Every document naming `MODEL_F`'s occupants goes stale in this change.** `AGENTS.md`,
`.env.example`, the Blueprint's tier and provider tables and its agent-to-abstract mapping, both
earlier ADRs' amendment blocks, ADR-046's current-state diagram, and the Executive Summary all
describe `MODEL_F` as holding exactly two gates. All are updated here. A future move of any gate
must repeat the same sweep, and the absence of a mechanical check for it is part of the cost above.

## Related

- [ADR-022](ADR-022-agent-model-tier-governance.md) — the Separation Invariant and the tier
  governance rules. Its rule 2 allocation of `@code-reviewer` to `MODEL_B` is superseded here; its
  rule 6 is why this is a new ADR rather than an edit to a tier table. Amended, not superseded.
- [ADR-046](ADR-046-gate-model-capability-floor.md) — the capability floor and the two-stage probe
  this decision is evaluated against, and the ADR that created `MODEL_F`. Its clause 5 occupancy is
  superseded here; its clauses 1 through 4 and 6 are what this decision complies with.
- [ADR-006](ADR-006-build-quality-gates.md) — the documentation-integrity build gates that partly
  backstop `MODEL_F`-authored prose.
- Blueprint §3.1 (the two degrees of independence), §3.2 (agent model matrix), §3.4 and §3.4.1 (the
  provider tables and the recorded probe verdicts, including this decision's).
- `AGENTS.md`, `.env.example`, `.opencode/opencode.json`, `tools/agent-trace.py`.
- EOP-178 (this decision).
