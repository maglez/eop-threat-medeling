# ADR-060: SonarQube runs locally and commits its report; CI ratchets three issue counts against a committed baseline with no server

**Status:** Accepted

**Date:** 2026-08-26

**Deciders:** @tech-lead, @architecture-guardian, @security-auditor

## Context

The operator ran this project through SonarQube Community Edition by hand and reported
68% coverage, 11 Reliability issues, 57 Maintainability issues and 2 Security issues,
then asked for a gate that keeps a record of each scan and fails the build if the issue
counts increase.

The project had no static-analysis *trend* of any kind. It has never been short of
static analysis — Checkstyle, SpotBugs, JaCoCo, the enforcer and nine
documentation-integrity tests all gate `./mvnw verify` — but every one of them checks an
absolute threshold or an individual violation. That catches the single bad line and says
nothing about direction. A codebase can add a tolerated finding per pull request
indefinitely without any of those gates registering that the total is climbing, because
none of them holds a number from yesterday to compare against. The specific thing that
was missing was memory.

Three constraints came out of the discovery interview, and all three shaped the design
more than the tooling did:

1. **No live SonarQube server in CI.** The operator's instruction was to "export a report
   locally and commit a baseline file to the repo, then compare in CI without a live
   server."
2. **A strict ratchet.** Any new issue of any of the three qualities fails the gate. Not a
   percentage, not a leak period, not a new-code-only view.
3. **The 68% coverage shortfall is explicitly out of scope.** JaCoCo already owns coverage
   at 80% instruction and 70% branch (ADR-006, ADR-031) and this story changes neither.

A fourth constraint was environmental: the local dashboard had to install without `sudo`
or an admin password. That turned out to be free — Colima's VM already reports
`vm.max_map_count = 1048576`, comfortably above the 262144 that SonarQube's Elasticsearch
bootstrap check demands, so the check is left **on** and nothing needs elevating.

### The reported numbers did not reproduce, and that matters

Scanning `main` produced 11 Reliability, **232** Maintainability, **0** Security and
**95.1%** coverage, against a reported 11 / 57 / 2 / 68%.

Reliability matches exactly. The other three do not, and only one of the discrepancies has
an established cause:

- **Coverage is explained.** 68% is what JaCoCo reports after `mvn test` alone; 95.1% is
  what it reports after `mvn verify`, when the failsafe integration tests have run and
  their execution data has merged into the same report. The operator's scan almost
  certainly followed `test` rather than `verify`. This is why the scan script runs a full
  `verify` before invoking the scanner, and it is not a detail: two scans of byte-identical
  source disagreeing by 27 coverage points must never be a thing a committed baseline can
  inherit by accident.
- **Maintainability 232 versus 57 and Security 0 versus 2 are unexplained.** They
  reconcile with neither the main-source subset (32 issues) nor the test subset (211) nor
  the total. A different scanner scope is the likeliest cause — a root `sonar-scanner` CLI
  invocation would also have analysed `ui/` TypeScript, which the Maven scanner does not
  see, and a New Code view would report a different population again. We could not
  determine it, and this ADR records that rather than inventing a cause. The committed
  baseline is the number we measured and can reproduce, not the number that was reported.

The scan is deterministic, which was verified rather than assumed: two independent scans
of an identical tree produced identical counts, an identical source hash, identical
coverage, `ncloc` and test totals, and all 243 issue fingerprints equal — a symmetric
difference of zero. The gate cannot flap on a rescan alone.

### Where the issues actually are

| Scope | Total | Maintainability | Reliability | Security |
|---|---|---|---|---|
| Main source | 32 | 31 | 1 | 0 |
| Test source | 211 | 201 | 10 | 0 |
| **Both** | **243** | **232** | **11** | **0** |

211 of 243 issues — 87% — are in test code. That is a direct consequence of a deliberate
project decision: the coverage floor is high, the suite is large (1386 tests), and test
code is scanned on the same footing as production code. It is recorded here because it
sets up the most likely way this gate will first cause friction, which the Consequences
section states in full.

> **Amended 2026-09-02 (EOP-000):** that friction arrived, and the answer was to narrow the
> gate rather than to raise the ceiling or stop scanning. The table above still describes
> what is *analysed* — all three rows are measured on every scan and all three are recorded
> in `sonar-report.json` — but only the **Main source** row is now *gated*. The whole-tree
> row is kept under `scope.ALL` so the project-overview figure stays reconcilable from the
> file. See the amendment at the end of the Consequences section.

Security is 0, not 2. Security Hotspots were checked separately — they live on a different
endpoint from issues and are not the same population — and that count is also 0.

## Decision

**SonarQube runs on the developer's machine. Its result is committed. CI compares two JSON
files and talks to nothing.**

Five parts:

### 1. A local, digest-pinned SonarQube (`compose.sonar.yml`)

A single service at `127.0.0.1:9000`, bound to loopback for the same reason InfluxDB is
(ADR-016): the server holds a browsable copy of our source. The image is pinned by digest,
which is the only digest-pinned image in the repository, and the reason is specific rather
than general hygiene — a SonarQube upgrade ships retuned rules, so a version drift moves
the baseline counts silently *and directionally*. Moving that pin is a
baseline-invalidating change and requires a re-measure in the same commit.

The embedded H2 database is kept and PostgreSQL was dropped, halving the stack. SonarQube
warns against embedded H2 for production instances and it is right to, but the durable
artefacts here are the committed JSON files under `tools/sonar/`; the database is a scratch
cache of one project's analysis, and recovery from losing it is `down -v` plus one rescan.

### 2. A local scan script (`tools/sonar/scan.sh`)

Runs `./mvnw -B verify`, then the scanner, then polls the Compute Engine to completion
before reading any counts — analysis is asynchronous and querying immediately returns the
previous run's numbers. It then harvests the measures, the main/test split and the full
issue inventory, writes the report, and finally runs the ratchet with `--tighten`.

The scanner is invoked **fully qualified** and is deliberately absent from `pom.xml`. That
keeps the property "Sonar is not part of `./mvnw verify`" structural rather than a matter
of having chosen not to bind an execution — someone would have to change the command, not
merely add a phase.

The scan token is passed through the environment, never as a `-D` argument, because a
system property is visible in `ps` to every other process on the machine.

### 3. A committed report and a committed baseline

`tools/sonar/sonar-report.json` is what the scan found. `tools/sonar/sonar-baseline.json`
is the ceiling. Both carry the three counts and a sorted multiset of issue fingerprints
shaped `QUALITIES|rule|repo-relative-path|hash`, where `hash` is SonarQube's own digest of
the offending line's *content* and therefore survives line-number shifts elsewhere in the
file. Storing fingerprints and not only totals is what lets a failure name the exact new
findings instead of reporting that a number went up.

The multiset is deliberate. A set would let a genuinely new finding hide behind an
identical existing one — same rule, same file, same line content occurring twice is
ordinary in test code.

### 4. A freshness hash (`tools/sonar/source-hash.sh`)

A committed report describes the tree it was generated from. Without a staleness check, a
developer who edits code and never rescans keeps a permanently green gate, and the gate is
theatre. The report therefore carries a `sourceHash` over `pom.xml` plus every `*.java`
under `src/main/java` and `src/test/java` — exactly the set the scanner analysed, with
`pom.xml` included because it decides which rules run. CI recomputes it and a mismatch
fails.

There is one definition of that token, in one sourced shell file used by both sides, for a
specific reason: two implementations of a hash pipeline agree until they do not, and the
failure presents as "stale report" on a tree that was scanned five seconds ago.

### 5. A CI job that compares and never writes (`sonar-ratchet`)

No service container, no scanner, no token, no JDK, no Node, no network call. It runs one
script, which recomputes the hash and compares two JSON files in about a second. Order of
operations is freshness first — a stale report's counts describe a different tree and are
meaningless, so there is nothing to be gained by comparing them.

The exit contract is **0 clean, 1 regression or stale report, 2 the gate itself could not
run**. A stale report is 1 rather than 2 on purpose: the developer changed code without
rescanning, so we do not know whether they added an issue, and not knowing must block.
Exit 2 is reserved for the gate being broken, which is not the developer's fault and
should read differently.

The job is **not** a required status check on arrival. `build` remains the only required
check.

### What was rejected

**An offline scanner CLI.** This is the option the operator's instruction most directly
described, and it does not exist. SonarQube removed dry-run/preview mode in version 5.2; a
modern scanner must reach a server to fetch quality profiles and to submit its analysis.
There is no supported way to produce an issue list from source alone.

**An ephemeral SonarQube inside every CI job.** This works, and is the honest escape hatch
if the chosen design's friction proves too high. It was rejected as the primary design on
two grounds: 60–120 seconds of container startup before the scan begins, for roughly 5–6
minutes added to every pull request; and it contradicts the operator's explicit choice.
Naming it here means the reversal is a decision someone can make rather than a design
someone has to rediscover.

**SonarCloud, or any hosted server.** Needs a repository secret and an account, and makes
every pull request depend on a third party's availability. Also unavailable to a fork.

**Wiring the scan into `./mvnw verify`.** Would make a local build require a running
container, and would turn a source edit red until a rescan. The gate is CI-only by choice.

**Ratcheting the main/test split separately, or per-rule.** The operator framed the request
as three numbers and the gate ratchets three numbers. The split is recorded in the report
as context and is never gated on — a second axis to satisfy doubles the ways a legitimate
refactor can be blocked.

**`ratchet.py --seed` instead of a separate `seed-baseline.py`.** Raising a ceiling and
enforcing one have opposite risk profiles, and one mistyped argument in CI would replace
the gate with a rubber stamp. Keeping them as separate files makes the difference visible
to `grep`, which is the only reason the claim "CI never seeds" is checkable at all.

**CI writing the tightened baseline back.** The ticket's literal wording asked for this.
See Consequences.

## Consequences

### What this buys

- The issue total cannot drift upward unobserved. It is the project's first
  static-analysis check with memory.
- A regression names the rule and the file, not just a number.
- The gate costs about a second of CI and needs no service, no secret and no JDK.
- Reproducing a CI failure locally is the same one command the job runs, with no `act`, no
  container and no secrets.
- A local dashboard exists for browsing findings, without `sudo`.
- Improvements tighten the ceiling automatically, so the gate gets stricter as the code
  gets better rather than needing someone to remember.

### What it costs, and what it does not prove

- **Any change under `src/main/java`, `src/test/java` or `pom.xml` requires a local rescan
  before this job passes.** This is the real cost, it is unavoidable given constraint 1,
  and it was judged inherent to the requirement rather than an artifact of the design —
  a developer has to scan to know whether they added an issue. It has a pleasant
  corollary: docs-only, workflow-only and `ui/`-only changes leave the hash untouched and
  pass instantly.
- **It does not prove the code is good.** It proves three integers did not increase. A
  codebase can sit at 232 maintainability issues forever and stay green.
- **It does not prove the report is honest.** A hand-edited `sonar-report.json` passes.
  The hash closes the *accident* of forgetting to rescan, not the *intent* to lie; review
  closes intent, exactly as it does for `tools/supply-chain/accepted-cves.json`.
- **It does not prove SonarQube's opinion is stable.** An upgrade retunes rules. Hence the
  digest pin and the recorded server version, and hence the rule that moving the pin means
  re-measuring in the same commit.
- **The scan is Java-only.** `ui/` TypeScript is not analysed by this gate. The `ui` job
  owns the front end and extending Sonar to it is deliberately deferred — it is also the
  most likely explanation for the operator's unreproduced numbers, which is an argument
  for doing it later rather than a reason to do it now.
- **`src/main/resources` is not in the hash.** A changelog or `application.yml` edit does
  not require a rescan, and the scanner does not analyse those files. If Sonar's scope is
  ever widened, the hash set must widen in the same commit or the freshness check silently
  stops covering what is scanned.
- **The gate's own tooling has no automated tests.** Nothing under `tools/sonar/` is
  exercised by `./mvnw verify`: Checkstyle, SpotBugs and JaCoCo see only `src/main/java`,
  and the 95.1% figure this ADR quotes says nothing about the six scripts that produce it.
  Their behaviour was verified by hand — the at-ceiling pass, a real added code smell, a
  stale report, a failing run leaving the baseline byte-identical, an end-to-end tighten,
  and three determinism samples — and that evidence is real but it is not repeatable by a
  machine. It is recorded here because the asymmetry matters: a gate that wrongly *fails*
  is loud and gets fixed within the hour, whereas a gate that wrongly *passes* is
  indistinguishable from a clean tree, and `ratchet.py` is the half that decides. During
  review, nine defects of a single class were found in `scan.sh` by successive human
  passes, which is a fair picture of what an untested shell script looks like under review
  rather than under test. The judgement is that this is acceptable **while the job is not a
  required check** — it advises rather than blocks, and the scripts it advises with are
  developer-local and fail loudly — and that it becomes a prerequisite rather than a
  nice-to-have at the moment `sonar-ratchet` is promoted, because from then on this code
  decides whether other people's pull requests merge. Adding a shell-test harness is
  itself an ADR-sized decision, which is why it is a named follow-up and not a quiet
  addition to this story.

### Two deviations from the ticket, both deliberate

**CI does not write the baseline.** The ticket's Definition of Done asked for CI to update
the baseline file when a pull request fixes an issue. It does not. A PR-triggered job
pushing a bot commit back to a contributor branch — potentially a fork — needs write
permission on a workflow that runs untrusted code, and is a usability hazard besides. The
same benefit is available for free by having the local scan tighten the baseline before the
commit is made, so `scan.sh` tightens and the developer commits it, while CI verifies
read-only and *reports* an improvement it declines to write. Both halves were verified,
including that a failing run leaves the baseline byte-identical.

**The files are under `tools/sonar/`, not `scripts/`.** The ticket guessed
`scripts/sonar-scan.sh` and a repository-root `sonar-baseline.json`. House convention puts
developer conveniences in `scripts/` and every *gate* under `tools/<area>/` with its JSON
beside it, as `tools/supply-chain/` and `tools/perf/` already do. Following the convention
beat following the ticket.

### The friction to expect first

With 201 of 232 maintainability issues in test code, a future story that raises coverage
will very likely trip this gate — new tests mean new test-code findings. That is the gate
working, not misfiring, and it has two honest answers: fix the new findings, or raise the
ceiling through `seed-baseline.py` as an argued, reviewed decision with the reason
recorded. What must not happen is the third answer, which is to stop running the scan; the
baseline's own header comment says so, in the same register as
`tools/supply-chain/accepted-cves.json`, because an entry in a ratchet is a liability to be
paid down rather than a dismissal.

### Why the job is not required yet

`build` stays the only required status check. The failure mode nobody has lived with is the
stale-report block: the first few times someone pushes a one-line Java fix and is told to
boot a container and rescan, that should be an annoyance rather than a merge blocker, so
the friction can be measured before it becomes mandatory. Two caveats, in the same terms
ADR-050 uses: branch protection is configured outside the workflow file, so no comment or
ADR can make the job required; and the job goes red on a regression either way, so "not
required" affects whether a merge is blocked, never whether the regression is visible.

### Verification

Every behaviour was exercised rather than reasoned about. Synthetically, from reports
derived from the real one: a wrong hash fails as stale and correctly distinguishes
"same files, contents differ" from "files added or removed"; a fabricated regression fails
and names both added findings; an improvement without `--tighten` passes and leaves the
baseline byte-identical; an improvement with `--tighten` lowers the ceiling and drops the
fixed fingerprint. End to end, with a real edit and a real scan: one unused private field
added to `Main.java` produced two genuine findings (`java:S1068` and `java:S1170`), the
un-rescanned tree blocked as stale, the rescanned tree blocked at 232 → 234 naming both
rules, the scan script propagated exit 1, and reverting the field — a real fix — took the
count back to 232 and tightened a deliberately raised ceiling back down.

### Amendment — 2026-09-02: the gate narrows to production code (EOP-000)

**The three gated integers now cover `src/main/java` only.** They fell from 11 Reliability /
232 Maintainability / 0 Security to **1 / 31 / 0** — not because anything was fixed, but
because 211 of the 243 findings were test-code findings that this gate no longer adjudicates.

The friction predicted above materialised exactly as described: with 87% of the findings in
test code and a ceiling carrying no headroom, a routine new test file turned the gate red for
reasons that said nothing about the product. The original ADR named three honest answers to a
tripped gate — fix the finding, argue the ceiling up, or the forbidden third of stopping the
scan. Narrowing scope is a fourth that this ADR did not contemplate, and it is admissible
only because it keeps the analysis intact and moves the *gate*:

- **Test code is still analysed**, still on the same footing, and still hashed by
  `source-hash.sh`, which is therefore unchanged. Its findings are measured on every scan and
  recorded under `scope.TEST`. What changed is that they no longer gate.
- **Nothing was excluded from the scanner.** `sonar.sources` and `sonar.tests` are still
  unset, so scope is still the Maven default. The narrowing is achieved by harvesting the
  gated inventory with `&scopes=MAIN` and taking the gated counts from the `scopes=MAIN`
  facet, so "production" keeps exactly one definition — SonarQube's own scope classification
  — rather than a second copy of the `src/main/java` path knowledge that `source-hash.sh`
  already holds.
- **`scope.ALL` was added** to the report, holding the whole-tree measures. `MAIN + TEST` must
  reconcile with it per quality, and `write-report.py` warns on stderr if it does not. That
  check replaces the cross-endpoint agreement the report used to get for free by taking its
  counts from `/api/measures/component` and its fingerprints from `/api/issues/search`.

**The reason was not coverage, and must not be restated as though it were.** SonarQube already
classified `src/test/java` under `sonar.tests`, which excludes it from `ncloc` and from the
coverage denominator, so coverage read 95.1% with test code fully in scope and reads 95.1%
after the change — measured, not argued. `ncloc` (7338) and `tests` (1386) are likewise
unchanged. Excluding test code from *analysis* would have bought nothing on any of those three
numbers while making 36,965 lines invisible; that option was considered and rejected.

**What this costs.** A green `sonar-ratchet` now says nothing whatever about test code. That is
a real reduction in what the gate proves and it is stated in `ratchet.py`'s own
"WHAT THIS GATE DOES NOT PROVE" list rather than only here. The 201 Maintainability and 10
Reliability findings under `src/test/java` remain in the report, unfixed and ungated.

**One knock-on in ADR-061.** That ADR justified `MODEL_B` being structurally ineligible for
this gate on the ground that `MODEL_B` authors test code and the gate reviews it. That
justification no longer holds, and the ADR-061 entry says so. `@sonarqube-expert` stays on
`MODEL_F` on capability and probe grounds, not separation grounds.

## Related

- [ADR-006](ADR-006-build-quality-gates.md) — the existing quality gates this one
  complements; Checkstyle, SpotBugs, JaCoCo and Javadoc, all absolute rather than
  directional. Also the source of the rule that a guard which can no longer fire is
  deleted rather than left passing, which applies here the day this ratchet's last
  finding is fixed
- [ADR-050](ADR-050-dependency-cve-scanning.md) — the closest structural precedent: a
  scanner in its own non-required CI job, gating against a committed allowlist whose header
  states that an entry is a liability rather than a dismissal
- [ADR-016](ADR-016-local-container-runtime.md) — Colima as the container runtime, the
  loopback-binding precedent, and the `vm.max_map_count` headroom that makes the local
  stack sudo-free
- [ADR-055](ADR-055-k6-performance-check-in-ci.md) — the other measurement gate with two
  incomparable populations, and the precedent for relaxed CI thresholds and a deferred
  promotion to required
