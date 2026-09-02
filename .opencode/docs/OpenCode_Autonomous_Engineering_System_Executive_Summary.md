# OpenCode Autonomous Engineering System — Executive Summary

> **Source document:** `OpenCode_Autonomous_Engineering_System_Blueprint.md`
> **Last reviewed:** 2026-09-02
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

- **The quality ratchet.** An automated scanner counts reliability, maintainability and security defects across the codebase, and those counts are held at a fixed ceiling. Any change that would raise a count — by even one — fails the build. The effect is that quality can improve but cannot silently degrade, which is a stronger guarantee than a dashboard nobody reads. A team may still choose to accept a specific finding, but that decision must be written down and argued in the same change, and the **SonarQube Expert** agent's role is to judge whether the argument is real.
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
| `@sonarqube-expert` | Measured code-quality defects have not increased, and any deliberate allowance was argued for in writing |
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

*Measured 2026-08-25 against `main`.*

### Codebase size

| Scope | Files | Total lines | Non-blank |
|---|---|---|---|
| Java — production (`src/main/java`) | 164 | 17,886 | 16,411 |
| Java — tests (`src/test/java`) | 136 | 36,956 | 32,090 |
| Front end — production (`ui/src`) | 17 | 3,784 | 3,410 |
| Front end — tests (`ui/src`) | 11 | 4,140 | 3,485 |
| Load-test scripts (`test/k6`) | 3 | 87 | — |
| **Total including tests** | **331** | **62,853** | **≈55,400** |
| **Total excluding tests** | **181** | **21,670** | **19,821** |

Test code accounts for **65%** of the codebase — a **1.90:1** test-to-production ratio. A further 2,942 lines of configuration (12 Liquibase changelogs plus Spring profile files) sit outside both totals.

### Documentation

| Metric | Count |
|---|---|
| Markdown documents under `docs/` | 66 (18,275 lines) |
| All tracked Markdown documents | 110 |
| Architecture Decision Records | 57 |
| Mermaid diagrams (under `docs/`) | 22, across 6 files |
| Mermaid diagrams (repository-wide) | 23, across 7 files |
| Reference PDFs | 4 |
| Total tracked files | 706 |

### Test suite

| Metric | Count |
|---|---|
| Java unit tests executed | 1,386 |
| Java integration tests executed (Testcontainers, PostgreSQL 17) | 13 |
| Front-end tests executed (Vitest, 11 files) | 259 |
| **Total automated tests** | **1,658** |

All 1,658 pass with zero failures, errors or skips.

### Test execution time

| Suite | Duration |
|---|---|
| Java unit tests (`./mvnw test`) | 46.6 s |
| Front end (`vitest run`) | 3.8 s |
| **Full quality gate (`./mvnw verify`)** | **1 min 11 s** |

The `verify` figure is the one that matters, because it is what every commit must pass: it runs the unit tests, the PostgreSQL integration tests, Checkstyle, SpotBugs, JaCoCo coverage thresholds, Javadoc and dependency enforcement in a single pass.

### Delivery volume

| Metric | Count |
|---|---|
| Jira tickets delivered or planned | 177 tickets |

The project does not use story-point estimation; throughput is tracked by ticket count, consistent with trunk-based delivery of one small story at a time.
