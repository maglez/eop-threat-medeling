# ADR-059: The code-review gate gets its own model tier, and the Separation Invariant becomes a build gate for code

**Status:** Accepted (2026-08-24)  
**Date:** 2026-08-24  
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor  

## Context

### The exception that would not close

[ADR-022](ADR-022-agent-model-tier-governance.md) rule 5 states the Separation Invariant: no artefact may be reviewed by a Definition-of-Done gate running on the same model that authored it. [ADR-046](ADR-046-gate-model-capability-floor.md) closed one of the two exceptions to it on 2026-08-21 by creating `MODEL_F` and moving `@architecture-guardian` and `@security-auditor` there, so that production code authored on `MODEL_A` is reviewed family-independently.

One exception survived that change, and it was the sharper of the two. Three of the five gates — `@code-reviewer`, `@tester-api` and `@tester-unit-and-quality` — all resolved to `amazon-bedrock/eu.anthropic.claude-sonnet-4-6`. The two testers *author* test code; `@code-reviewer` *reviews* it. Not merely the same family: the **same model identifier**, so identical weights judged identical weights. Neither degree of independence defined in Blueprint §3.1 applied — not family-independence, not even the weaker model-independence.

### Why it could not be closed by rearranging the existing tiers

The cause is arithmetic rather than allocation. Six tier variables existed, but only **five distinct models** stood behind them, because `MODEL_E` is deliberately the same identifier as `MODEL_C` (a separate variable so the front end can be repointed without disturbing the back-end builders). Five gates plus every authoring agent drawn from five models leaves no assignment in which each gate avoids every author of what it reviews. Some pair must collide.

EOP-178 attempted the rearrangement anyway, moving `@code-reviewer` onto the existing `MODEL_F`. The work was completed and passed all five gates, and the operator then **withdrew it**: PR #305 was closed unmerged, its branch deleted, and `main` never touched. The reason was sound. `MODEL_F` already holds `@architecture-guardian`, which *authors* ADRs and C4 models, so the change moved the same-model-identifier overlap from test code onto architecture documentation instead of removing it. Its own ADR conceded the point in the phrase "relocated, not eliminated". Moving a weakness is not closing it.

**Number 058 is deliberately left unused.** It was allocated to that withdrawn attempt, and step 1 of the process in [README.md](README.md) forbids reusing a number "even for a superseded ADR". The gap is recorded here and in the README alongside the existing ADR-001 gap, and left alone.

### What a real fix requires

A sixth distinct model. Not a sixth variable — a sixth set of weights, in a family none of the authoring tiers occupy.

## Decision

### 1. `MODEL_G` is introduced, holding `@code-reviewer` alone

`@code-reviewer` moves out of the `MODEL_B` group in `.opencode/opencode.json` and into a new `MODEL_G` group. `MODEL_B` retains `@product-owner`, `@tester-api`, `@tester-unit-and-quality`, `@expert-kent-beck` and `@expert-dave-farley`. `MODEL_F` is **unchanged**, keeping `@architecture-guardian` and `@security-auditor`.

`MODEL_G` resolves to `amazon-bedrock/zai.glm-5` — a sixth family (Z.AI), family-independent of Anthropic (`MODEL_A`/`MODEL_B`/`MODEL_D`), of Qwen (`MODEL_C`/`MODEL_E`) and of MiniMax (`MODEL_F`). It clears the ADR-046 capability floor with 202,752 context and 101,376 max output tokens against floors of 128,000 and 40,000.

`.envrc` now asserts seven variables, and `.env.example` carries `MODEL_G` in both the Bedrock and the Zen block at column 0 so `tools/switch-provider.sh` continues to toggle it. As with `MODEL_F`, the Zen-route entry `opencode/glm-5` is marked **unprobed**: it is the same model reached through a different provider, and ADR-046 clause 4 requires a probe per route, not per model.

### 2. `@security-auditor` deliberately stays on `MODEL_F`

Considered and rejected: giving `@security-auditor` a seventh tier in the same change. Rejected because the overlap it would close is materially weaker than the one this ADR closes — architecture *documentation* rather than code — and because each additional tier multiplies the drift surface across the tier tables that ADR-022 rule 6 exists to control. The consequence is stated openly below rather than left for a reader to discover.

### 3. The probe verdict is fresh, not inherited

`zai.glm-5` passed the 2026-08-21 two-stage probe as runner-up, but that probe screened for **architecture and security-audit** work. ADR-046 clause 4 requires a passing verdict *for the work the pin will do*, and EOP-178 set the precedent by re-probing its own candidate rather than inheriting its verdict. A fresh **code-review-specific** two-stage probe was therefore run, with a control run against the incumbent `MODEL_B`, and its verdict is recorded as a dated sub-block under Blueprint §3.4.1. Both candidate and control passed every graded axis. The 2026-08-21 verdict was explicitly not treated as transferable.

### 4. The invariant becomes machine-checked for code

`src/test/java/org/maglez/eop/docs/SeparationInvariantTest.java` joins the nine existing repository-integrity gates and runs in the ordinary `verify` phase. It reads `.opencode/opencode.json` and the active block of `.env.example` from the working tree — no OpenCode installation, no environment variable — resolves every agent to a concrete model identifier, and **fails the build** when a gate shares an identifier with an agent authoring an artefact class that gate reviews.

Three design choices follow the precedent set by [ADR-052](ADR-052-having-value-mandate-is-build-enforced.md):

- **It fails loudly rather than skipping.** A tier variable the agent block references but the active `.env.example` block does not define raises `IllegalStateException` naming both. A guard that silently passes when it cannot evaluate itself is worse than no guard.
- **The semantic mappings are declared in the test**, gate→artefact-class and author→artefact-class, each with a comment tying it to ADR-022 rule 5. No configuration file encodes which agent writes what; that is a judgement, and it belongs in reviewable source rather than in inference.
- **The surviving overlap is a declared, justified, self-retiring allow-list entry.** A missing justification fails. An entry that no longer describes a real overlap *also* fails, forcing deletion rather than letting a stale exemption decay quietly — the failure mode [ADR-006](ADR-006-build-quality-gates.md) records for the branch-coverage limit.

## Consequences

### What is now true

The Separation Invariant holds **with zero exceptions for production code, infrastructure and test code** — the three artefact classes ADR-022 rule 5 enumerates. Test code authored on `MODEL_B` is reviewed from `MODEL_G`, a different family; production code authored on `MODEL_A` or delegated to `MODEL_C`/`MODEL_E` is reviewed from `MODEL_F` and `MODEL_G`, both family-independent of both; infrastructure authored on `MODEL_C` likewise. For the first time the claim is checked by the build rather than asserted in prose.

### What is not true, and must never be claimed

**This ADR does not make the Separation Invariant unconditional.** `@security-auditor` and `@architecture-guardian` remain on `MODEL_F` together, and `@architecture-guardian` authors ADRs and C4 models. **Architecture documentation is therefore still reviewed by a tier-mate at one model identifier — neither model- nor family-independent.** This very document is an instance: it was reviewed by a gate sharing weights with the agent that co-authored it.

Any phrasing of this change as "unconditional", "complete separation" or "zero exceptions" **without** the production-code / infrastructure / test-code qualifier is a defect, and a reviewer must reject it. EOP-178 was withdrawn for exactly that over-claim.

Closing the remainder means pinning `@security-auditor` to a seventh distinct tier and deleting the allow-list entry, which the test will then demand. Until then the entry carries its own justification and the build states the position rather than hiding it.

### Other costs, stated plainly

- **Nothing prevents the reviewer from being wrong about a different thing.** Family-independence defends against a *shared* blind spot. It does not make `zai.glm-5` a better reviewer than `claude-sonnet-4-6`, and the control run showed the incumbent's reasoning to be the more precise of the two on the probe target. This change buys independence, not quality.
- **The mappings can go stale.** If a new agent is added, or an existing one starts authoring a class it did not before, the test keeps passing until someone updates its declared maps. The floors (`MINIMUM_AGENTS`, `MINIMUM_TIERS_IN_USE`, `MINIMUM_COMPARISONS`) stop it passing by matching *nothing*, but they cannot notice a semantic omission.
- **Only the code classes are mechanised.** The architecture-documentation overlap is declared, not enforced away. As with ADR-052, one clause is machine-checked and the rest stays reviewer-enforced, and saying so is part of the decision.
- **A seventh variable is a seventh thing to keep in step**, across `.envrc`, both blocks of `.env.example`, the Blueprint's tier tables, `AGENTS.md`, the Executive Summary and `tools/agent-trace.py`. ADR-022 rule 6 already forbids changing allocation by editing a tier table; this ADR widens the surface that rule governs.
- **The pin does not apply to the session that made it.** Model assignments are read at startup, so the `@code-reviewer` verdict on *this* story ran on `MODEL_B`. The gate round for EOP-179 is therefore evidence that the change is sound, not evidence that `MODEL_G` reviewed it. Only a session started after this commit exercises the new tier.
- **`zai.glm-5` costs more than the tier it replaces for this agent** — $1.00/$3.20 per million tokens against `MODEL_F`'s $0.30/$1.20, though below `MODEL_B`'s $3.00/$15.00. Net cost for code review falls.

## Related

- [ADR-022](ADR-022-agent-model-tier-governance.md) — agent model tier governance; rule 5 states the Separation Invariant and rule 6 forbids editing tier tables to reallocate. Amended 2026-08-24 to cite this ADR.
- [ADR-046](ADR-046-gate-model-capability-floor.md) — the gate capability floor and the two-stage probe requirement; created `MODEL_F` and closed the production-code exception. Amended 2026-08-24, because its statement that nothing enforces any clause programmatically is no longer wholly true.
- **ADR-058** — number allocated to the withdrawn EOP-178 attempt, which moved `@code-reviewer` onto the existing `MODEL_F`. PR #305 was closed unmerged and the number is left permanently unused.
- [ADR-052](ADR-052-having-value-mandate-is-build-enforced.md) — the precedent this test follows: mechanise the checkable clause, declare the rest as reviewer-enforced, and add a documented allow-list entry rather than relaxing the comparison.
- [ADR-006](ADR-006-build-quality-gates.md) — the rule that a guard which can no longer fire must be fixed or deleted, never left green.
- Blueprint §3.1 (the two degrees of independence), §3.2 (agent model matrix), §3.4 and §3.4.1 (provider architecture and probe verdicts), §12.8 (the five-gate Definition of Done).
