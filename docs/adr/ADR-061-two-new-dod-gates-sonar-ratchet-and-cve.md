# ADR-061: The Definition of Done grows from five gates to seven — a SonarQube ratchet adjudicator and a CVE adjudicator, both on `MODEL_F`

**Status:** Accepted (2026-09-02)  
**Date:** 2026-09-02  
**Deciders:** @tech-lead, @architecture-guardian, @security-auditor  

## Context

### Two agents arrived that did not match the machinery they were named after

The operator authored two subagent definitions, `.opencode/agents/sonarqube-expert.md` and `.opencode/agents/dependency-vulnerability.md`, and asked for both to run on every code change, to reject a change that degrades quality or introduces a vulnerability, and for the code-authoring agents to know the standard they will be held to.

The intent was sound. The definitions, as drafted, contradicted two accepted ADRs.

`sonarqube-expert.md` declared its own baseline at `.opencode/sonar-baseline.json` and populated it with metrics the agent computed itself — total and maximum cognitive complexity, maximum cyclomatic complexity, code-smell and bug totals, and an **estimated** line and branch coverage percentage. It also carried `edit: allow` so it could write that file, and the body was truncated mid-sentence.

[ADR-060](ADR-060-sonarqube-issue-ratchet.md), accepted six days earlier, had already built this gate on the opposite principle. A digest-pinned SonarQube container measures the tree, `tools/sonar/scan.sh` harvests the result into a committed `tools/sonar/sonar-report.json`, and `tools/sonar/ratchet.sh` compares three integers against a committed `tools/sonar/sonar-baseline.json`. Determinism was verified: two scans of an identical tree produced identical counts, an identical source hash, identical coverage and ncloc, and all issue fingerprints matching with a symmetric difference of zero. A second baseline in a second location, holding numbers an LLM estimated rather than a scanner measured, would not have been a redundant check — it would have been a rival source of truth that no rerun could reproduce.

`dependency-vulnerability.md` declared a zero-tolerance gate: reject if any dependency carries a known flaw of **any** severity, Low included. It reached for `cargo audit`, `pip-audit`, `govulncheck` and `mvn dependency-check`, and named `requirements.txt`, `Cargo.toml` and `go.mod`. It never mentioned Trivy.

[ADR-050](ADR-050-dependency-cve-scanning.md) had settled all of this. Trivy, pinned by version *and* by the SHA-256 of the release asset, scans the two trees the application actually ships — `pom.xml` and `ui/package-lock.json`. There is no Rust, Python or Go tree. `dependency-check-maven` was considered and rejected: it needs an NVD API key, and this repository deliberately holds no secrets beyond the built-in `GITHUB_TOKEN`. And the severity policy was not an oversight but the ADR's central judgement — gate on HIGH and CRITICAL, print MEDIUM and LOW without gating, because a medium advisory in a third-party transitive is frequently unreachable, often has no in-range fix, and arrives at a volume set by other people's release cadence. In that ADR's words, gating on medium "is how a scanner earns its reputation for noise and then gets disabled".

### Adding a gate is not a documentation change

The five-gate Definition of Done is load-bearing in seven places: the `agent` block of `.opencode/opencode.json`, the sign-off gate in `.opencode/agents/tech-lead.md`, `AGENTS.md`, four Blueprint sections, `tools/agent-trace.py`, and — since ADR-059 — a build gate, `SeparationInvariantTest`, that fails `./mvnw verify` when a Definition-of-Done gate shares a model identifier with an agent that authored an artefact class that gate reviews.

That last one is what makes a sixth or seventh gate a design decision rather than a file addition. A gate whose key is missing from the `agent` block silently resolves to `MODEL_A`, which is where `@tech-lead` authors production code. The failure mode is not an error message; it is a gate reviewing its own tier-mate's work while appearing to be configured.

### The capability floor demanded fresh probes, and they did not go as expected

[ADR-046](ADR-046-gate-model-capability-floor.md) clause 4 requires a Definition-of-Done gate to sit on a tier with a recorded, passing two-stage probe for the role it performs, and ADR-059 established that a verdict is **role-specific and not transferable** — `zai.glm-5` already held a passing architecture-and-security verdict, and a fresh code-review probe was run anyway rather than inheriting it.

So both new roles needed probing even though `MODEL_F` and `MODEL_G` are already probed tiers. Four rounds were run against fixtures built from the repository's own tooling, each stage graded on four axes: a real tool call, a verbatim quote of stdout, the true exit code, and a terminal `VERDICT:` line.

**`MODEL_G` failed the sonar-ratchet role, twice, on the same axis.** Dispatched against a fixture where the ratchet exits 1, it reported "Exit code: 0" — on the first fixture and again on the second. Everything else was right: the command, the verbatim output, the count table, the refusal to invent evidence, the correct `VERDICT: REJECT`. But a gate that misreads the exit status of the command it exists to run is a gate that can approve a red build while quoting the failure, which is the precise behaviour the Sign-off Contract's "actual command output rather than intent" clause was written to prevent.

**`MODEL_F` failed the same role on its first attempt, differently and worse.** It read the exit code correctly, and then invented rule definitions for both findings — asserting that `java:S3516` concerns direct use of standard output and that `java:S6218` concerns `Collectors.toList()`. Neither is what those rules are. Having searched for the constructs it had invented and not found them, it concluded that both genuine findings were probably scanner false positives. Fabricating grounds to dismiss a finding is a worse failure in a gate than misreading an exit code, because it manufactures a reason to approve.

**The `MODEL_B` control passed cleanly, and is structurally ineligible.** It read both exit codes correctly and located both findings exactly. It cannot hold this gate: `MODEL_B` carries `@tester-api` and `@tester-unit-and-quality`, both of which author test code, and this gate reviews test code — 211 of the 243 measured findings sit under `src/test/java`. Pinning it there would recreate exactly the same-identifier overlap that ADR-059 was written to close.

> **Amended 2026-09-02 (EOP-000):** the second half of this justification no longer holds.
> On the same day, [ADR-060](ADR-060-sonarqube-issue-ratchet.md) was amended to narrow the
> ratchet to production code, so this gate no longer reviews test code and `GATE_REVIEWS`
> records it as `PRODUCTION_CODE` alone. `MODEL_B` authors no production code, so the
> separation invariant would not forbid the pin today. `@sonarqube-expert` stays on
> `MODEL_F` on the grounds that survive: the ADR-046 capability floor and the recorded
> two-stage probe. `MODEL_B` has no probe for this role, and a gate pin with no recorded
> passing verdict is non-compliant regardless of which tier it sits on.

### One of the two failures was partly the probe's fault

`MODEL_F` observed, correctly, that the issue fingerprint `QUALITIES|rule|path|hash` carries **no line numbers**. The hash is SonarQube's digest of the offending line's *content*, not its position. The probe brief had demanded a `file:line` citation for every finding — evidence the tooling does not emit and the gate therefore cannot be asked for.

That is a defect in the brief, and it was corrected. It does not excuse either failure: `MODEL_G`'s exit-code misreport and `MODEL_F`'s invented rule titles are both independent of it, and the honest answer to an unanswerable question is to say so, which is what the control did and what `MODEL_F` did once the brief was fixed.

## Decision

### 1. `sonarqube-expert` adjudicates ADR-060's artefacts and computes nothing

The agent reads `tools/sonar/sonar-report.json` and `tools/sonar/sonar-baseline.json`, runs `tools/sonar/ratchet.sh`, and rules on what that command printed and returned. It judges the three gated integers and nothing else.

- **`.opencode/sonar-baseline.json` does not exist and must never be created.** There is one baseline, it is committed under `tools/sonar/`, and it is written by `tools/sonar/seed-baseline.py` or lowered in place by `ratchet.sh --tighten`.
- **The agent never passes `--tighten`.** That flag lowers ceilings when a scan finds fewer issues, and it belongs to the local `scan.sh` path. A gate that can mutate the baseline it judges is not a gate. `edit: deny` in frontmatter, and the agent definition names the specific files it may not touch by any means.
- **No estimated metrics.** Cognitive complexity, cyclomatic complexity and coverage are not gated by the ratchet, and an LLM's estimate of them is not evidence. Coverage in particular belongs to JaCoCo at 80% instruction and 70% branch; the figure in the scan report is context only, and the agent is instructed that it may not reject on it.
- **A stale report is a rejection, not a caveat.** `ratchet.sh` verifies the report's `sourceHash` against a hash over `pom.xml` and every Java source. A stale report's counts describe a different tree, so not knowing must block. Because the hash covers only those paths, a documentation-only, workflow-only or `ui/`-only change needs no rescan.
- **A finding is cited as the tool prints it** — quality, rule key, path. The fingerprint carries no line number, so the agent may not supply one, and it may not state what a rule means unless the title can be quoted from a file in this repository. It may not characterise a finding as a false positive: the ratchet is a count comparison, and adjudicating a rule's correctness is out of scope.
- **The judgement the agent actually adds is the ceiling raise.** Raising a number in the baseline is always available and almost never right. A raise is a Blocker unless it lands in the same commit as the code, names the rule that fired, and argues why living with the finding beats fixing it.

### 2. `dependency-vulnerability` adjudicates ADR-050's scan and keeps its severity policy

The agent runs `tools/supply-chain/scan-dependencies.sh`, which takes no arguments, and rules on its output and exit status.

- **HIGH and CRITICAL gate. MEDIUM and LOW are reported and do not.** The agent is explicitly instructed not to reject on a medium or low finding and not to describe the policy as a weakness. ADR-050's reasoning is reproduced in the agent definition so the constraint travels with it.
- **Pass 2 can never fail the gate.** The `--include-dev-deps` pass prints what is visible only at build time and is informational by construction.
- **Suppressions are judged, not counted.** An entry in `tools/supply-chain/accepted-cves.json` is keyed `ID@module` — because on 2026-08-22 one advisory was reported against two distinct jackson-databind coordinates in a single scan, and a bare-ID key would have suppressed both while documenting one. All eleven fields are mandatory, and `reachability` must name the file, the symbol and the guard; "not exploitable in our use case" with no trace is not a reachability argument. An entry added to turn a red job green is a Blocker.
- **The allowlist fails in both directions**, and the remedy for an entry no longer reported is to **delete** it — never to widen it, never to change a dependency version to keep it alive.
- **The two allowlists are never interchangeable.** `accepted-cves.json` is about code that reaches production; `accepted-advisories.json` is about the OpenCode plugins on a developer's machine. Different scanner, different identifier space, different subject. An entry may not be copied between them.
- Everything the scan does not establish is stated in the agent definition: container images are deferred, dependency convergence belongs to `maven-enforcer-plugin`, and there is no typosquat or licence analysis.

### 3. Both gates pin to `MODEL_F`, on fresh two-stage probes

`.opencode/opencode.json` places both in the existing `MODEL_F` group, which now holds four agents: `@architecture-guardian`, `@security-auditor`, `@sonarqube-expert` and `@dependency-vulnerability`. No new environment variable, no `.envrc` change, no `.env.example` change.

`MODEL_F` is `amazon-bedrock/minimax.minimax-m2.5`, already cleared against the ADR-046 capability floor. The two probes recorded in Blueprint §3.4.1 are role-specific and fresh:

- **The CVE role passed first time, both stages.** Stage 1 planted a deliberately stale allowlist entry; the gate exits 1 on the both-directions check. `MODEL_F` read the exit code correctly, quoted the failure, ruled that the remedy was to delete the entry rather than widen it or move a version, quoted the `reachability` field and judged it unacceptable against ADR-050's trace requirement, and correctly held that the two allowlists are separate. Stage 2 on the pristine tree returned exit 0 and `VERDICT: APPROVE` with six genuine scope limits volunteered.
- **The sonar-ratchet role passed on the re-scoped brief, both stages,** dispatched through the same agent that had failed the over-specified one, so the comparison isolates the brief. Stage 1: exit code 1 correct, counts exact, and on the citation bound the honest answer — the rule titles are not available from the artefacts in scope. No invented rule description and no false-positive adjudication: both of the first round's failures were gone. Stage 2: exit code 0, correct approval.

Two imprecisions are recorded rather than smoothed over. On the CVE role `MODEL_F` tagged its own finding MEDIUM where the control tagged it BLOCKER, conflating an advisory's severity with the severity of a red gate — the agent definition now states that distinction explicitly. On the Sonar role it described SonarQube's superseded issue types as categories outside the three gated ones, when the three software qualities *are* the whole taxonomy, and it raised an unnecessary nit about a zero security count that ADR-060 already answers. Neither affected a verdict.

**`MODEL_G` is recorded as probed and failed for the sonar-ratchet role.** It keeps `@code-reviewer`, whose own probe stands.

### 4. The Definition of Done becomes seven approvals, and the invariant test grows with it

`.opencode/agents/tech-lead.md` now requires seven sign-offs, and `tools/agent-trace.py` carries both new names in `PIPELINE["2 gateways"]`, `AUDITOR_AGENTS` and — because both author nothing at any stage — `READ_ONLY_AGENTS`.

`SeparationInvariantTest` gains two `GATE_REVIEWS` entries: `@sonarqube-expert` reviews production and test code, `@dependency-vulnerability` reviews production code and infrastructure. `MINIMUM_AGENTS` rises from 15 to 17 and `MINIMUM_COMPARISONS` from 18 to 27, the true comparison count having risen from 21 to 30.

> **Amended 2026-09-02 (EOP-000):** `@sonarqube-expert`'s entry narrowed to
> `PRODUCTION_CODE` when the ratchet narrowed to production code
> ([ADR-060](ADR-060-sonarqube-issue-ratchet.md) as amended). That retired its three
> comparisons against the three test-code authors — `@ui-builder` and the two tester gates
> — taking the true comparison count from thirty to twenty-seven, so `MINIMUM_COMPARISONS`
> was lowered from 27 to 24 to restore the margin its own Javadoc requires. `MINIMUM_AGENTS`
> stays at 17 and `ALLOWED_OVERLAPS` stays at exactly one entry: the narrowing removes a
> review relationship rather than creating an exception.

`AGENT_AUTHORS` is unchanged at eight entries, because neither new agent authors anything. **`ALLOWED_OVERLAPS` is unchanged at exactly one entry**: the only author on `MODEL_F` is `@architecture-guardian`, which authors architecture documentation, and neither new gate reviews that class. Adding two agents to that tier therefore introduces no new overlap and requires no new declared exception.

### 5. The code writers are told, in the rules they already load

One bullet is added to `.opencode/rules/build-quality.md`, which every agent loads through the existing `.opencode/rules/*.md` glob and which already owns the Checkstyle, SpotBugs, JaCoCo and Javadoc gates. No sixteenth rules file: the directory is loaded wholesale, and a rule about Sonar counts belongs beside the other build gates rather than in a file of its own.

## Consequences

### What is now true

- Both agents adjudicate the repository's real, deterministic tooling. Neither computes a metric, and neither can write the artefact it judges.
- Both tiers carry a recorded, role-specific, passing two-stage probe, so both pins satisfy ADR-046 clause 4.
- The Definition of Done is seven approvals, and `./mvnw verify` fails if either new gate is repinned onto a tier that authored an artefact class it reviews.
- The probe round is documented including its failures. `MODEL_G` is on record as unsuitable for this role, and the reason is specific enough to re-test.

### What is not true, and must never be claimed

- **These agents are not the enforcement.** The `sonar-ratchet` and `dependency-cve` CI jobs are, and both were built to compare committed files without an LLM in the path precisely so they could not drift. The agents add judgement about whether a ceiling raise was argued and whether a suppression is a real reachability trace — questions a script cannot answer. Do not describe either agent as the thing that stops a regression.
- **Neither CI job is a required status check yet.** ADR-050 explains why for the CVE job: it goes red on a third party's publication against unchanged code. ADR-060 explains why for the ratchet. A seventh sign-off does not change that.
- **The Separation Invariant still has exactly one exception, and it is not closed by this ADR.** `@security-auditor` shares `MODEL_F` with `@architecture-guardian`, which authors ADRs and C4 models — including this file. Architecture documentation is still reviewed by a tier-mate at one model identifier: neither family- nor model-independent. This change adds two agents to that tier without widening the exception, because neither authors anything, but it does not narrow it either.
- **A passing Sonar ratchet does not mean the code is clean.** It means the three counts did not rise. Passing at the ceiling means no progress on the existing findings, only no regression.
- **A clean CVE scan means no *known* HIGH or CRITICAL advisory in two dependency trees.** It is not a statement about the container image, about reachability of a medium finding, or about whether the code is secure.

### Other costs, stated plainly

- `MODEL_F` now holds four of the thirteen delivery agents, including three of the seven gates. A future failure of that model, or a decision to repin it, now moves more of the review surface at once than it did with two.
- Every story now pays for two more sign-offs. The `/goal` budgets in Blueprint §12.8 were sized for five reviewers plus one remediation cycle and have been re-sized; a story that previously fitted may now need a second window.
- The probe round cost eight dispatches across four rounds, two of which existed only because the first brief demanded evidence the tooling does not emit. The lesson is recorded here: probe against the artefacts the tool actually produces, or the probe measures the brief rather than the model.
- Nothing checks that these two agents are ever actually dispatched. That is the same gap the other five gates have — the Tech Lead's sign-off gate is prose, the plugin's completion audit inspects the claim rather than re-running it, and a fabricated or vacuous approval is caught by review or not at all.

## Related

- [ADR-022](ADR-022-agent-model-tier-governance.md) — model tier governance and the Separation Invariant
- [ADR-046](ADR-046-gate-model-capability-floor.md) — the capability floor and the two-stage probe requirement
- [ADR-050](ADR-050-dependency-cve-scanning.md) — Trivy, the HIGH/CRITICAL policy and `accepted-cves.json`
- [ADR-059](ADR-059-code-review-gate-on-its-own-model-tier.md) — role-specific probe verdicts, and the invariant as a build gate
- [ADR-060](ADR-060-sonarqube-issue-ratchet.md) — the ratchet, the committed baseline and the freshness hash
