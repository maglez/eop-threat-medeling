# OpenCode Autonomous Engineering System — Executive Summary

> **Source document:** `OpenCode_Autonomous_Engineering_System_Blueprint.md`
> **Last reviewed:** 2026-09-03
> **Audience:** Business stakeholders

---

## What This System Is

OpenCode Autonomous Engineering is a structured, AI-powered software delivery system that operates like a **senior engineering team working around the clock** — without the coordination overhead, the context-switching, or the risk of a single person's blind spots reaching production.

Rather than using AI as a tool that writes individual snippets of code, this system organises AI into **seventeen specialised agents**, each with a distinct role, a defined scope of responsibility, and — critically — **independent review gates** that check each other's work before anything reaches users.

The result: engineers spend their time on **strategy, architecture, and business decisions**, not on writing boilerplate or chasing down bugs that should never have shipped.

---

## The Problem It Solves

Traditional software delivery suffers from predictable, recurring failures:

- **Requirements get lost in translation** between the business and the development team.
- **Bugs reach production** because the same person who wrote the code also reviewed it.
- **Architectural shortcuts accumulate** into technical debt that slows every future feature.
- **Security vulnerabilities** are discovered after deployment, not before.
- **Performance degrades silently** until a user complains.
- **Knowledge lives in people's heads**, not in the codebase.

This system addresses each of these at the structural level — not through process documents or good intentions, but through automated gates that cannot be bypassed.

---

## How Requirements Are Gathered

Before a single line of code is written, the **Product Owner agent** conducts a structured discovery interview with the person requesting the feature. This is not a form to fill in — it is a genuine conversation designed to:

- Separate **what the business needs** from **how someone thinks it should be built** (a common source of wasted effort).
- Surface **edge cases and constraints** that are easy to miss in a brief.
- Review the proposed solution against **accessibility standards** (GOV.UK Design System / WCAG 2.2 AA) at design time — a human-reviewed checkpoint, not an automated test.
- Produce **precise, testable acceptance criteria** in plain language — so there is no ambiguity about whether a feature is done.

Only once the requirements pass this review are they filed as formal stories in Jira and handed to the engineering team. This single step eliminates the most common cause of rework: building the wrong thing.

---

## Built-In Engineering Best Practices

Every story delivered by this system passes through a mandatory quality pipeline. These are not optional checks — they are **automated gates that block delivery if they fail**.

### ✅ Automated Testing at Every Level

| Test Type | What It Checks | When It Runs |
|---|---|---|
| **Unit tests** | Every function and business rule in isolation — sub-second, no external dependencies | On every code change |
| **API integration tests** | Every endpoint behaves correctly end-to-end | On every code change |
| **Performance tests** | Response times and error rates under realistic load (p95 < 200ms) | Nightly and on every deployment |

Code coverage is enforced: at least **80% of instructions** and **70% of branches** must be tested. A story cannot be marked done if coverage drops below these thresholds.

### ✅ Continuous Integration & Deployment

Every change goes through an automated pipeline before it can reach users:

1. Code is compiled and all tests run.
2. Static analysis checks for known bug patterns and security issues.
3. Code style is enforced automatically.
4. Documentation integrity is verified (claims in docs are checked against the actual code).
5. If everything passes, the change is **automatically deployed to production**.

There is no manual "release day". Changes flow continuously, in small increments, which means problems are caught early and rollbacks — when needed — are trivial.

### ✅ Architecture Reviewed on Every Change

The **Architecture Guardian** agent reviews every pull request against the system's architectural principles. It checks that:

- New code does not introduce hidden dependencies that will cause problems later.
- The structure of the system remains clean and maintainable as it grows.
- Architectural decisions are documented in **Architecture Decision Records (ADRs)** — a permanent, searchable log of *why* the system is built the way it is.

This means architectural debt is caught at the point of introduction, not discovered months later during a painful refactor.

### ✅ Security Audited Before Every Merge

A dedicated **Security Auditor** agent — running on a completely different AI model family from the one that wrote the code — reviews every change for:

- Input validation vulnerabilities.
- Secrets accidentally included in code.
- Privilege escalation risks.
- Dependency vulnerabilities.

Security is not a phase at the end of a project. It is a gate on every single change.

### ✅ Code Quality and Third-Party Risk Are Ratcheted, Not Merely Measured

Two further gates, added in September 2026, stop the two problems that quietly accumulate on every software project: code quality drifting downwards one shortcut at a time, and known vulnerabilities arriving inside third-party libraries nobody chose to change.

- **The quality ratchet.** An automated scanner counts reliability, maintainability and security defects across the codebase, and those counts are held at a fixed ceiling. Any change that would raise a count — by even one — fails the build. The effect is that quality can improve but cannot silently degrade, which is a stronger guarantee than a dashboard nobody reads. It runs as **two independent ratchets**, one over the back-end code and one over the front end, each with its own ceiling, so a new defect in one language cannot be paid for out of the other's headroom. A team may still choose to accept a specific finding, but that decision must be written down and argued in the same change, and the **SonarQube Expert** agent's role is to judge whether the argument is real.
- **The dependency scan.** Every third-party library the product actually ships — both back-end and front-end — is scanned for published vulnerabilities on every change, and anything rated high or critical fails the build. Where a vulnerability genuinely cannot affect this product, that exemption must record *why*, tracing the specific reason the vulnerable code is unreachable. The **Dependency Vulnerability** agent checks that the trace holds, and that exemptions are deleted once they stop being needed rather than left to rot.

Both scans fail mechanically, with no AI in the decision. The two agents exist because a script can count defects but cannot tell a considered engineering trade-off from an excuse. Neither agent can edit the files it is judging, so neither can approve its own way out.

### ✅ Performance Tested Continuously

The **Performance Engineer** agent runs load tests nightly and after every significant deployment. Results are tracked over time so that **performance regressions are caught before users notice them**, not after.

---

## The Expert Advisory Panel

Four expert personas have been synthesised from **hundreds of hours of video content, books, conference talks, and published work** by four of the most respected figures in software engineering:

| Expert | Specialisation | Known For |
|---|---|---|
| **Uncle Bob (Robert C. Martin)** | Clean code, SOLID principles, software craftsmanship | *Clean Code*, *Clean Architecture* |
| **Dave Farley** | Continuous delivery, test-driven development, fast feedback loops | *Continuous Delivery* (with Jez Humble) |
| **Kent Beck** | Test-driven development, Extreme Programming, incremental design | Inventor of TDD and XP |
| **Alex Xu** | Ultra-high-scale distributed systems, system design | *System Design Interview* series |

These advisors are available to any agent in the system for a second opinion on a design decision, a trade-off, or an architectural choice. They do not write code or file tickets — their role is purely advisory, ensuring that the team's decisions are grounded in proven, battle-tested engineering wisdom.

---

## Seven Independent Review Gates

No story can be declared complete until **seven independent agents** have each issued an explicit approval. These reviewers are deliberately assigned to a **different AI model family from the one that wrote the code**, so that a reviewer does not inherit the author's blind spots.

For **production code, infrastructure and test code** there are zero exceptions, a build gate fails the build if one reappears. One documented exception remains: **architecture documentation** — the ADRs and C4 models — is reviewed by an agent running the same model identifier as the agent that wrote it, because the architecture and security reviewers share a tier. That tier now holds four agents, and the exception did not widen when it grew: the two reviewers added in September 2026 author nothing at all, so there is nothing either of them could be asked to review of its own making.

| Gate | What It Approves |
|---|---|
| `@tester-unit-and-quality` | Unit tests are comprehensive and meaningful |
| `@tester-api` | API contracts are correct and integration tests pass |
| `@security-auditor` | No security regressions introduced |
| `@code-reviewer` | Code is clean, maintainable, and follows SOLID principles |
| `@architecture-guardian` | Architectural integrity is preserved |
| `@sonarqube-expert` | Measured code-quality defects have not increased in either the back-end or the front-end scan, and any deliberate allowance was argued for in writing |
| `@dependency-vulnerability` | No new high or critical vulnerability in any third-party library the product ships |

The last two gates were added in September 2026 and work differently from the first five in a way worth understanding. Both sit in front of an automated scan that already fails the build on its own, with no AI involved in the decision. Their job is the judgement the scan cannot make: whether a team's decision to *accept* a known defect or a known vulnerability was genuinely reasoned and documented, rather than waved through to make a red light go green. Neither agent is permitted to edit the files it is judging, so neither can grant itself a pass.

An eighth automated backstop then checks that all seven approvals are genuinely present and evidenced before a story can be archived as done. It is described here as a safety net rather than an independent review, because it runs on the same underlying model as the orchestrator whose work it is checking — another limitation the project documents rather than overstates.

---

## How a Feature Reaches Users Safely

Features are deployed to production **before they are visible to users**, hidden behind a feature flag. This means:

- The code is live and proven in a real environment before anyone switches it on.
- The business decides **when** to release a feature, independently of when it was built.
- If something unexpected is found after release, the feature can be turned off in seconds — without a deployment, without a rollback, without downtime.

```
Business request
      ↓
Product Owner interviews stakeholder → Requirements frozen → Jira stories filed
      ↓
Tech Lead orchestrates delivery team
      ↓
Seven independent review gates all approve
      ↓
Code deployed to production (feature flag OFF — invisible to users)
      ↓
Business approves release → Feature flag ON → Users see the feature
```

---

## What This Means for the Business

| Concern | How This System Addresses It |
|---|---|
| **"We've been burned by requirements misunderstandings before"** | The Product Owner interview separates business need from technical assumption before any work starts. |
| **"We need to move faster without breaking things"** | Continuous deployment with automated gates means small, safe changes ship daily rather than risky big-bang releases. |
| **"We've had security incidents in the past"** | Security is audited on every single change by an independent model, not as a periodic review. |
| **"Performance has degraded without warning before"** | Nightly performance tests with historical trend tracking catch regressions before users do. |
| **"We've accumulated a lot of technical debt"** | Architecture is reviewed on every PR. Debt is caught at the point of introduction. |
| **"We don't know why past decisions were made"** | Every architectural decision is documented in a permanent, searchable ADR log. |
| **"We want confidence that AI isn't just making things up"** | Seven independent review gates, drawn from different AI model families than the author (with one documented exception, for architecture documentation, noted above), must all approve before anything ships. |

---

## In Summary

This system delivers software with the discipline of a senior engineering team, the consistency of an automated pipeline, and the breadth of expertise that no single team could sustain. It does not replace human judgement — it amplifies it, by ensuring that every decision is informed, every change is verified, and every release is intentional.

**The engineer's role becomes setting direction, making strategic decisions, and reviewing work that has already been tested, secured, and validated before it reaches their desk.**

---

## Some Metrics

*Measured 2026-09-04 on `main` at commit `d9de808`, with a clean working tree.*

### Codebase size

| Scope | Files | Total lines | Non-blank |
|---|---|---|---|
| Java — production (`src/main/java`) | 167 | 18,212 | 16,718 |
| Java — tests (`src/test/java`) | 139 | 38,025 | 33,021 |
| Front end — production (`ui/src`) | 17 | 3,784 | 3,411 |
| Front end — tests (`ui/src`) | 11 | 4,140 | 3,485 |
| Load-test scripts (`test/k6`) | 3 | 87 | 78 |
| **Total including tests** | **337** | **64,248** | **56,713** |
| **Total excluding tests** | **184** | **21,996** | **20,129** |

Test code accounts for **66%** of the codebase — a **1.92:1** test-to-production ratio. A further 3,078 lines of configuration (12 Liquibase changelogs plus Spring profile and test-resource files) sit outside both totals.

The front-end rows count every `.ts`, `.tsx` and `.css` file under `ui/src`, split by the `*.test.*` filename convention — so the 11 test files here are the same 11 Vitest files counted below, while the 227-line stylesheet and the 41-line Vitest setup harness fall under production. The figures carried before the earlier 2026-09-03 refresh (16 files, 3,752 lines) could not be reproduced by any counting rule, so they were replaced rather than adjusted; the extension filter is necessary because `ui/src` also holds the tracked card images. The *excluding tests* row covers Java and front-end production code only, since the k6 scripts are themselves tests.

### Documentation

| Metric | Count |
|---|---|
| Markdown documents under `docs/` | 71 (19,315 lines) |
| All tracked Markdown documents | 115 |
| Architecture Decision Records | 61 |
| Mermaid diagrams (under `docs/`) | 22, across 6 files |
| Mermaid diagrams (repository-wide) | 23, across 7 files |
| Reference PDFs | 4 |
| Total tracked files | 732 |

The two Mermaid rows are lower than the figures carried here on 2026-09-02 (23 across 7, and 26 across 10). They are a direct count of ```` ```mermaid ```` fences in tracked Markdown, so the earlier numbers were either measured by a looser method or have since gone stale; the count above is the reproducible one.

### Test suite

| Metric | Count |
|---|---|
| Java unit tests executed | 1,416 |
| Java integration tests executed (Testcontainers, PostgreSQL 17) | 13 |
| Front-end tests executed (Vitest, 11 files) | 259 |
| **Total automated tests** | **1,688** |

All 1,688 pass with zero failures, errors or skips. JaCoCo analysed 150 classes and every coverage threshold was met.

### Static analysis (SonarQube)

Since 2026-09-03 there are **two SonarQube projects rather than one**: `eop-threat-modeling` over the Java back end (`src/main/java` plus `src/test/java`) and `eop-threat-modeling-ui` over the front end (`ui/src`). Each has its own ceiling, its own committed evidence file and its own CI ratchet job, and the two fail independently. The split is deliberate — a single polyglot project would let a new front-end defect be paid for out of the Java project's headroom and vice versa, so one combined number would keep moving truthfully while saying nothing about either population.

Each table carries two columns, because the answer differs depending on what you count. **Whole project** is the figure the SonarQube dashboard shows, covering production and test code together. **Production code only** covers the code that actually ships — `src/main/java` for the back end, the non-test TypeScript under `ui/src` for the front end — and is the scope each CI ratchet gates.

#### Back end — Java (`eop-threat-modeling`)

| Metric | Whole project | Production code only |
|---|---|---|
| Security | **A** — 0 issues | **A** — 0 issues |
| Reliability | **C** — 8 issues (4 medium, 4 low) | **C** — 1 issue (medium) |
| Maintainability | **A** — 227 issues (2 high, 146 medium, 79 low) | **A** — 26 issues (2 high, 7 medium, 17 low) |
| Security hotspots | **A** — 0 hotspots to review | **A** — 0 hotspots to review |
| Coverage | 95.3% (97.2% line, 89.3% branch) | 95.3% — the same figure; see below |
| Duplications | **0.0% — no duplicated lines at all** | 0.0% — none |
| Maintainability debt | 19.5 hours estimated remediation, 0.5% debt ratio | 2.1 hours estimated remediation |
| Lines of code analysed | 7,350 across 307 files | — |

#### Front end — TypeScript (`eop-threat-modeling-ui`)

| Metric | Whole project | Production code only |
|---|---|---|
| Security | **A** — 0 issues | **A** — 0 issues |
| Reliability | **B** — 9 issues (all low) | **B** — the same 9 issues; all sit in production |
| Maintainability | **A** — 31 issues (2 high, 16 medium, 13 low) | **A** — 26 issues (2 high, 13 medium, 11 low) |
| Security hotspots | **A** — 0 hotspots to review | **A** — 0 hotspots to review |
| Coverage | 90.8% (91.4% line, 88.3% branch) | 90.8% — the same figure; see below |
| Duplications | 1.9% — 72 lines in 4 blocks | 1.9% — the same 72 lines; see below |
| Maintainability debt | 2.3 hours estimated remediation, 0.2% debt ratio | 2.0 hours estimated remediation |
| Lines of code analysed | 2,814 across 30 files | — |

Both quality gates are **passing**. Two clarifications about the letters, because the scale does not apply uniformly:

- **Only four of these metrics carry an A–E rating.** SonarQube rates Security, Reliability, Maintainability and Security Review (the hotspot rating). Coverage and Duplications have no letter — they are percentages the quality gate judges pass or fail, so the figures above are the percentages themselves rather than an invented grade.
- **The Security hotspot A is vacuous in both projects, and should be read as such.** A rating of A on zero hotspots means there was nothing to review, not that a review found nothing. It is worth reporting only as evidence that no hotspot has been introduced and left unexamined.

Reliability is the one metric not at A in either project, and for different reasons — SonarQube rates on the *worst* finding rather than on a count, so a single finding sets the letter.

- **Back end (C).** The single production finding is a `java:S6218` at `TrustedProxies.java:131` — a value class holding an array whose `equals` compares references rather than contents — and one medium finding caps production reliability at C on its own. No production reliability finding is worse than medium, and the two high-severity findings are all maintainability ones. The seven remaining reliability findings are in test code: three regex-backtracking warnings and four assertion-precision ones. Two successive stories have now reduced the maintainability counts and neither touched reliability: the `java:S3516` blocker reported here before 2026-09-03 was fixed by EOP-187, and EOP-190 then removed four more findings on 2026-09-04 — three `java:S107` "too many parameters" (which are the high-severity ones, hence five falling to two) and, as a side effect of the same extraction, one `java:S3776` cognitive-complexity finding.
- **Front end (B).** All nine reliability findings are **low** severity, which is what holds the letter at B rather than C: three `typescript:S6772` (`App.tsx`, `LobbyScreen.tsx` ×2) and six `typescript:S7781` (`GameScreen.tsx` ×4, `FollowSuitHint.tsx`, `GameOverScreen.tsx`). All nine sit in production code, and all nine are *also* counted as maintainability findings — so the front-end rows overlap rather than add: 31 distinct issues, not 40.

Neither project carries a single security issue, and neither carries an unreviewed hotspot.

How to read the last three rows in each table. **Coverage is identical in both columns by construction** — SonarQube excludes test files from the coverage denominator, so 95.3% and 90.8% have always been production-code figures. One caveat on the front-end number: `ui/src/main.tsx` is the React mount point, has no test, and is deliberately left in scope reporting 0% rather than excluded, so 90.8% is a little lower than the component tests alone would suggest. **All remaining duplicated lines are in front-end production code** — the back end has none, and the front end's four blocks are in `CreateSessionForm.tsx` and `JoinSessionForm.tsx`, so that row is the same number twice there. The back end reported 0.2% — 42 lines in two blocks — until 2026-09-04, when EOP-190 deleted exactly those two blocks: they were the twice-written trick-resolution cascade in `ResolveTrickUseCase` and `PlayCardUseCase`, now extracted into a single `TrickJournal`. The debt figures are the ones that diverge, and much more sharply on the back end: of its 19.5 hours only 2.1 sits in shipping code and the balance is in the test suite, whereas the front end's 2.3 hours is almost all production (2.0 hours, against 17 minutes in tests). That back-end asymmetry is exactly what the CI ratchet was narrowed to production scope to avoid. Both back-end debt figures were re-measured for this refresh, so unlike the previous revision of this section they are current rather than carried forward.

One asymmetry between the two projects is worth stating plainly, because it is intentional rather than an oversight: **front-end coverage is measured and reported but never gated.** JaCoCo enforces floors on the Java side (80% instruction, 70% branch); there is no equivalent limit on the front end, so a change halving the 90.8% would pass provided it introduced no new issue.

The two tables were measured at different commits, and unlike the rest of this section neither was re-measured in full. The back-end issue counts, coverage and lines-of-code were refreshed by a rescan at commit `74d914c`; the front-end table was last scanned at `d293a28` and is unchanged. Both committed reports' freshness hashes were recomputed against the current tree and match, so the counts in both tables are current for the code as it stands. The server-only figures — the letter ratings, duplications, hotspot counts and debt hours — behave differently in the two tables this time. The **back end's were re-measured**, by querying the running server directly (`/api/measures/component` for duplications, the coverage split and the whole-project debt, and `/api/issues/search?scopes=MAIN` for the production-only debt), which is how the duplication row could be updated with a measured `0.0%` rather than dated as unverified. The **front end's were not**, because its committed report is unchanged and its freshness token excludes `.java`, so nothing in this story could have moved them. No letter rating changed in either project.

These figures come from a local SonarQube server (26.8.0) and are the only numbers in this section that cannot be reproduced offline. The two committed evidence files — `tools/sonar/sonar-report.json` and `tools/sonar/sonar-ui-report.json` — carry the issue counts, coverage and lines of code, but not the letter ratings, the duplication figures, the hotspot count or the debt estimate; those exist only on the server. Refreshing these tables therefore means running `tools/sonar/scan.sh` (back end, via the Maven scanner) and `tools/sonar/scan-ui.sh` (front end, via a digest-pinned `sonar-scanner-cli` container) against a running instance.

### Known vulnerabilities

Vulnerability counts in this project come from **three separate populations**, and they must not be added together — they have different subjects, different scanners and different consequences. Two of the three are enforced in CI as jobs that can fail a merge; the third is the source-code security rating already reported above.

#### 1. Shipped dependencies — the code that reaches a user

Scanned by **Trivy 0.74.0** over the two manifests that describe what actually runs in production: `pom.xml` (the resolved Maven graph, walked transitively) and `ui/package-lock.json` (the front-end lockfile). Measured 2026-09-04.

| Severity | Java back end | Front end | Fails the build? |
|---|---|---|---|
| **CRITICAL** | 0 | 0 | Yes |
| **HIGH** | 0 | 0 | Yes |
| **MEDIUM** | 0 | 0 | No — reported only |
| **LOW** | 0 | 0 | No — reported only |
| Packages examined | 103 | 6 | — |

**Zero known vulnerabilities at every severity, in the code that ships.** Two things make that figure meaningful rather than merely reassuring:

- **Nothing is suppressed.** The scan has an allowlist for advisories a human has traced and found unreachable (`tools/supply-chain/accepted-cves.json`), and it is **empty**. The clean result is on merit, not by exception. The allowlist is also fail-loud in both directions: an entry that is no longer reported turns the job red, so a suppression cannot quietly outlive the thing it suppressed.
- **The scan is not empty.** 103 Maven packages and 6 front-end runtime packages were examined, spanning Spring Boot, Liquibase, the PostgreSQL driver and the React runtime.

A second, **informational** pass adds the build-time and test-only dependencies — 374 front-end packages once devDependencies are included — and also finds **zero at every severity**. That pass never gates, because a defect in a test framework cannot be reached by a request to the deployed application. One honest gap: the informational pass adds no Maven packages (103 in both passes), so the Java test-scope libraries — JUnit, Testcontainers, Mockito, AssertJ — are not covered by either pass.

One deliberate over-report worth knowing about: the scanner reads the *resolved* dependency graph rather than the final artifact, so H2 (a test-only database, excluded from the shipped jar) is in the 103. An H2 advisory would therefore fail the gate even though H2 never reaches production. Failing loudly in that direction is accepted.

#### 2. Application source code — findings in code written here

Already reported in the tables above, restated here because it is the question people usually mean by "how many vulnerabilities":

| Project | Security rating | Security issues | Security hotspots |
|---|---|---|---|
| Java back end | **A** | 0 | 0 |
| Front end | **A** | 0 | 0 |

Neither project carries a single security issue, and neither carries a hotspot awaiting review. As noted above, an A on zero hotspots means there was nothing to review rather than that a review found nothing.

#### 3. Developer tooling — the seven OpenCode plugins

This is the one population with findings, and it is the one that **never reaches a user**. The seven OpenCode plugins run on a developer's machine, not in the deployed product; they are audited separately (`tools/supply-chain/audit-plugins.sh`) over a 210-package tree.

| Severity | Advisories | Status |
|---|---|---|
| **CRITICAL** | 0 | — |
| **HIGH** | 3 | All three traced to an unreachable code path and allowlisted |
| **MODERATE** | 7 | Reported, not gated |
| **LOW** | 4 | Reported, not gated |
| **Total distinct advisories** | **14** | Audit result: **PASS** |

Twelve of the fourteen are the same dependency: `undici` 5.29.0, pulled in through a single chain from one plugin (`smart-title` → `ai` → `@ai-sdk/gateway` → `@ai-sdk/provider-utils` → `undici`). Every one of the three high-severity advisories is a defect in undici's **WebSocket client**, and the traced reason all three are unreachable is recorded in `tools/supply-chain/accepted-advisories.json` on two independent grounds: the only consumer of undici in the tree imports `Agent` and `fetch` and never constructs a WebSocket, and the import itself sits behind a runtime check that is false under the JavaScript engine OpenCode actually uses. There is no in-range upgrade — the vulnerable version is pinned two levels up by a package this project does not control.

The same audit verifies supply-chain provenance: **210 of 210 packages have verified registry signatures**, 76 carry attestations, and 3 of the 7 pinned plugins publish a SLSA provenance attestation. The remaining four are recorded as a known, accepted residual rather than left undeclared.

#### Summary

| Population | Critical | High | Moderate/Medium | Low |
|---|---|---|---|---|
| Shipped dependencies | 0 | 0 | 0 | 0 |
| Application source code | 0 | 0 | 0 | 0 |
| Developer tooling (never shipped) | 0 | 3 (all traced unreachable) | 7 | 4 |

**In the product itself — its own code and every dependency that ships with it — there are no known vulnerabilities at any severity.**

### Test execution time

| Suite | Duration |
|---|---|
| Java unit tests (`./mvnw test`) | 38.5 s |
| Front end (`vitest run`) | 6.4 s |
| **Full quality gate (`./mvnw clean verify`)** | **1 min 14 s** |

The `verify` figure is the one that matters, because it is what every commit must pass: it runs the unit tests, the PostgreSQL integration tests, Checkstyle, SpotBugs, JaCoCo coverage thresholds, Javadoc and dependency enforcement in a single pass.

### Delivery volume

| Metric | Count |
|---|---|
| Jira tickets delivered or planned | 177 tickets (carried forward from 2026-08-25 — see note) |
| Highest issue key referenced in git history | `EOP-190` |
| Distinct issue keys referenced in commit subjects | 100 |
| Commits on the current branch | 639 |

The project does not use story-point estimation; throughput is tracked by ticket count, consistent with trunk-based delivery of one small story at a time.

Only the last three figures were re-measured on 2026-09-04: they come from the repository itself and can be reproduced offline. The ticket total could not be, because the Jira board was again unreachable when this section was refreshed, so it remains the 2026-08-25 figure carried forward and should be read as a floor rather than a current count — the highest key in the history is already `EOP-190`. The distinct-key figure is much lower than the ticket total for two expected reasons: a ticket that is planned but not yet started leaves no trace in git at all, and a story delivered as a single squashed commit contributes one subject line however many commits preceded it.
