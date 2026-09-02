# ADR-062: `MODEL_A` moves from the `eu.` inference profile to `global.`, because a client-side payload change made the EU profile uninvocable

**Status:** Accepted (2026-09-02)  
**Date:** 2026-09-02  
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor  

## Context

### Every request on the primary tier started failing, and no repository change caused it

On 2026-09-02 every request issued on `MODEL_A` — the tier holding `@tech-lead`, `@expert-alex-xu` and `@expert-uncle-bod`, and the tier the goal plugin's completion auditor inherits — failed with:

```
thinking.adaptive.block_binding.prefix_mismatch_behavior: Extra inputs are not permitted
```

The cause is client-side and is not in this repository. The OpenCode binary at `~/.opencode/bin/opencode` was updated to `1.18.26` on 2026-09-01 at 22:10. That build sends an adaptive-thinking block inside `additionalModelRequestFields` for every model in a hard-coded list:

```javascript
var UQ = ["claude-opus-4-7", "claude-opus-4-8", "claude-opus-5", "claude-fable-5", "claude-sonnet-5"];
```

and, when the reasoning config carries a block binding, adds the `thinking-binding-controls-2026-08-01` beta and a `block_binding: { prefix_mismatch_behavior: … }` member. `amazon-bedrock/eu.anthropic.claude-opus-5` rejects that member outright. The failure is total rather than degraded: no request on the tier completes.

Three facts bound the problem. First, **`claude-opus-5` is in that list and `claude-sonnet-4-6` and `claude-haiku-4-5` are not**, so `MODEL_B` and `MODEL_D` — the other two Anthropic tiers, both on `eu.` — were unaffected throughout and are not touched by this decision. Second, the field is emitted by the *client*, so no configuration in `.opencode/opencode.json` and no agent frontmatter can suppress it. Third, the operator had already worked around it interactively via the `/models` TUI picker, which selected `amazon-bedrock/global.anthropic.claude-opus-5` for the running session — so the tracked configuration and the model actually in use had silently diverged, and `.env` had additionally been hand-edited to a *bare* `amazon-bedrock/anthropic.claude-opus-5`, a third value agreeing with neither.

### The `eu.` prefix here was availability, not a residency commitment

[ADR-046](ADR-046-gate-model-capability-floor.md) §Consequences established a three-way distinction that this decision has to be read against: an `eu.`-prefixed Anthropic identifier carries a genuine EU inference profile; a **bare** identifier "introduces no residency posture beyond the one the repository already has — but it does not improve on it", with in-region behaviour "stated as unverified rather than resolved in either direction"; and an explicitly cross-region `global.` profile is "a change of posture rather than a continuation of it". ADR-046 used that last clause to **reject** `global.openai.gpt-5.6-luna` for two audit gates.

Asked directly whether EU-only inference was a requirement, the operator answered that it is not:

> "it is not about data residency but more about what my AWS account has at my disposal since we use the servers in London. Is Global works, then we are fine"

That answer is what makes this decision available rather than blocked. The `eu.` prefix on the three Anthropic tiers reflected what the account could invoke from `eu-west-2`, not a commitment anyone had made about where inference is served. Four of the seven tiers — `MODEL_C`, `MODEL_E`, `MODEL_F`, `MODEL_G` — were already bare identifiers with unverified routing, so the project was not verifiably EU-resident before this change either.

### The three available options

1. **Pin the OpenCode binary below `1.18.26`.** Keeps `eu.` and needs no configuration change, but freezes the entire toolchain — plugins, goal state, the TUI — against a single upstream payload field, and offers no exit: the field will not be removed, so the pin becomes permanent.
2. **Drop to the bare `amazon-bedrock/anthropic.claude-opus-5`.** Works, and is what `.env` had been hand-edited to. But it moves the primary tier onto exactly the "unverified rather than resolved" footing ADR-046 describes, which is worse than a known cross-region profile: it is not in-region *and* it does not say so.
3. **Take `amazon-bedrock/global.anthropic.claude-opus-5`.** Works, is explicit about being cross-region, and leaves the toolchain free to move.

## Decision

### 1. `MODEL_A` is `amazon-bedrock/global.anthropic.claude-opus-5`

Option 3. Four sites carry the identifier and all four move together:

- `.env` — the live value read by direnv. Gitignored, so it is not part of this commit, but it must agree with the rest or the tracked configuration is fiction
- `.env.example:72` — the active, column-zero assignment. This one is machine-read: `SeparationInvariantTest` parses it with `^(MODEL_[A-Z])=(\S+)\s*$` and resolves every agent's model through it
- Blueprint §3.4.1, the live Bedrock identifier table — the `MODEL_A` row
- Blueprint §3.4.1, the commented `.env.example` mirror further down the same section

The commented **Zen** block in `.env` and `.env.example` (`#MODEL_A=opencode/claude-opus-5`) is deliberately untouched: Zen model names carry no region prefix, so there is nothing there to move.

`MODEL_B` and `MODEL_D` **stay on `eu.`**, and not out of caution — `claude-sonnet-4-6` and `claude-haiku-4-5` are absent from the client's adaptive-thinking list, so the payload that breaks Opus is never sent for them. If that list grows, they move the same way, and the same reasoning applies without a further ADR.

### 2. The separation invariant is unaffected, and this was verified rather than assumed

`SeparationInvariantTest` resolves each agent to a model *identifier* and reports an overlap when a gate shares an identifier with a different agent that authors an artefact class the gate reviews. Changing a prefix cannot move that: `global.anthropic.claude-opus-5` still matches the parser's regex, is still distinct from `MODEL_B` and `MODEL_D`, and is still in the **Anthropic** family. So no family boundary moves, `MINIMUM_TIERS_IN_USE` stays 6, and `ALLOWED_OVERLAPS` stays at exactly one entry. `./mvnw -o -q test -Dtest=SeparationInvariantTest` was run and exits 0.

The [ADR-046](ADR-046-gate-model-capability-floor.md) capability floor is likewise untouched. It binds the seven Definition-of-Done gates, which sit on `MODEL_B`, `MODEL_F` and `MODEL_G`; `MODEL_A` holds no gate at all, and has held none since 2026-08-21. No probe verdict recorded in Blueprint §3.4.1 is invalidated, because a probe verdict attaches to a model's behaviour on a role, and this is the same model reached by a different routing profile.

### 3. ADR-046's rejection of `global.openai.gpt-5.6-luna` stands as written

This decision does not overturn it and must not be cited as doing so. That rejection concerned a **gate** for which a bare identifier was equally available, so a cross-region profile bought nothing and cost posture. Here the choice is between a cross-region profile and a broken tier. Different tier, different constraint, same distinction applied honestly in both cases.

### 4. `AWS_REGION` does not change

`AWS_REGION=eu-west-2` selects the runtime endpoint a request is *sent to*, not where inference is *served*. Editing it would neither restore in-region inference nor undo this change. It stays as it is.

## Consequences

### What is now true

- The primary tier works again, on `amazon-bedrock/global.anthropic.claude-opus-5`, and the tracked configuration agrees with the model actually in use for the first time since the binary update — the `/models` override, the hand-edited `.env` and `.env.example` had drifted to three different values
- Two of the seven tiers carry an EU inference profile (`MODEL_B`, `MODEL_D`), down from three. One carries an explicitly cross-region profile (`MODEL_A`). Four are bare identifiers with routing that has never been established in either direction
- The toolchain is free to move. Nothing in this repository now depends on an OpenCode build older than `1.18.26`
- Blueprint §3.4.1 carries the reasoning inline, next to the table a reader would otherwise copy the identifier from

### What is not true, and must never be claimed

- **This project is not EU-resident, and was not before this change either.** Four tiers were already bare identifiers. Saying "we run in London" describes `AWS_REGION`, which is a different fact about a different thing
- **This is not a model upgrade or a version bump.** It is the same model — `claude-opus-5` — reached by a different inference profile. Nothing about capability, context window or output limit changed, so nothing recorded about `MODEL_A`'s behaviour needs re-measuring
- **`global.` is not a general licence.** It is one tier, forced by one client-side field, recorded here. A second tier moving to `global.` needs its own reason written down; it does not inherit this one
- **The `eu.` prefix on `MODEL_B` and `MODEL_D` is not a guarantee.** They are on `eu.` because it works. Do not build anything on the assumption that it will keep working across OpenCode releases

### Other costs, stated plainly

- **The forcing constraint is upstream and can change again without notice.** A client that adds a field can add another, and the same failure mode — total, on one tier, with a message naming a field nobody in this repository wrote — will recur. The diagnosis path is recorded here so it need not be rediscovered: read the model list out of the binary and check whether the tier's model is in it
- **`.env` is gitignored, so one of the four sites cannot be enforced.** A developer whose `.env` still holds the `eu.` value gets a dead primary tier and no signal pointing at this ADR. Nothing checks the agreement between `.env` and `.env.example`, and this change does not add such a check
- **Reverting is a two-part operation.** Restoring `eu.` requires pinning the OpenCode binary below `1.18.26` as well as changing the identifier; doing only the second reinstates the failure
- **The OpenCode binary is outside every supply-chain control this repository has, and saying it is "client-side" is not the same as saying that.** `tools/supply-chain/audit-plugins.sh` audits the seven pinned npm plugins against `expected-plugins.json`, and `tools/supply-chain/scan-dependencies.sh` scans the two shipped dependency trees. The binary lives at `~/.opencode/bin/opencode`, outside the worktree, and is neither a plugin nor an application dependency, so **no** mechanism in this repository pins it, records its version, verifies its provenance or notices when it moves. That is how a change nobody made here broke a tier overnight with no signal beyond the failure itself. It is uncovered surface, stated as uncovered rather than left to be inferred from the word "client-side", and this change does not close it

## Related

- [ADR-022](ADR-022-agent-model-tier-governance.md) — model tier governance: what a tier is, and the two degrees of review independence any separation claim must be stated in
- [ADR-046](ADR-046-gate-model-capability-floor.md) — the gate capability floor, and the `eu.` / bare / `global.` distinction this decision is read against
- [ADR-059](ADR-059-code-review-gate-on-its-own-model-tier.md) — `MODEL_G`, and the precedent that a probe verdict is role-specific
- [ADR-061](ADR-061-two-new-dod-gates-sonar-ratchet-and-cve.md) — the seven Definition-of-Done gates, none of which sit on `MODEL_A`
