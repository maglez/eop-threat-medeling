# OpenCode Autonomous Engineering System — Executive Summary

> **Source document:** `OpenCode_Autonomous_Engineering_System_Blueprint.md`
> **Last reviewed:** 2026-08-24
> **Audience:** Business stakeholders

---

## What This System Is

OpenCode Autonomous Engineering is a structured, AI-powered software delivery system that operates like a **senior engineering team working around the clock** — without the coordination overhead, the context-switching, or the risk of a single person's blind spots reaching production.

Rather than using AI as a tool that writes individual snippets of code, this system organises AI into **fifteen specialised agents**, each with a distinct role, a defined scope of responsibility, and — critically — **independent review gates** that check each other's work before anything reaches users.

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
- Validate the proposed solution against **accessibility standards** (GOV.UK / WCAG 2.2 AA) before any work begins.
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

## Five Independent Review Gates

No story can be declared complete until **five independent agents** have each issued an explicit approval. These agents are assigned to AI model families that are **different from the models that wrote the code** — a deliberate design choice that prevents the same blind spots from appearing in both the author and the reviewer.

| Gate | What It Approves |
|---|---|
| `@tester-unit-and-quality` | Unit tests are comprehensive and meaningful |
| `@tester-api` | API contracts are correct and integration tests pass |
| `@security-auditor` | No security regressions introduced |
| `@code-reviewer` | Code is clean, maintainable, and follows SOLID principles |
| `@architecture-guardian` | Architectural integrity is preserved |

A sixth automated backstop — the **Goal Plugin auditor** — independently verifies that all five approvals are genuine before archiving the story as done.

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
Five independent review gates all approve
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
| **"We want confidence that AI isn't just making things up"** | Five independent review gates, using different AI model families, must all approve before anything ships. |

---

## In Summary

This system delivers software with the discipline of a senior engineering team, the consistency of an automated pipeline, and the breadth of expertise that no single team could sustain. It does not replace human judgement — it amplifies it, by ensuring that every decision is informed, every change is verified, and every release is intentional.

**The engineer's role becomes what it should always have been: setting direction, making strategic decisions, and reviewing work that has already been tested, secured, and validated before it reaches their desk.**
