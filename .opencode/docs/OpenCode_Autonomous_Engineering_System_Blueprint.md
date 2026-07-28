# OpenCode Autonomous Engineering System Blueprint

Architectural Blueprint, Decision Rationale, Multi-Model Diversity, and Operational Guardrail Protocols

---

## Table of Contents

- [1. Introduction & Core Objective](#1-introduction--core-objective)
- [2. Architectural Foundations & Delivery Paradigms](#2-architectural-foundations--delivery-paradigms)
  - [2.1 Walking Skeleton First](#21-walking-skeleton-first)
  - [2.2 Trunk-Based Development](#22-trunk-based-development-over-gitflow)
  - [2.3 Continuous Deployment](#23-continuous-deployment-deploy-every-passing-commit)
  - [2.4 Feature Flags](#24-decoupling-deployment-from-release-feature-flags)
- [3. Multi-Agent Architecture & Model Allocation](#3-multi-agent-architecture--multi-model-allocation-strategy)
  - [3.1 Defence-in-Depth Model Allocation](#31-defence-in-depth-model-allocation)
  - [3.2 Agent Model Matrix](#32-agent-model-matrix)
  - [3.3 Agent Responsibilities](#33-agent-responsibilities)
  - [3.4 Provider Architecture](#34-provider-architecture)
- [4. Expert Advisory System](#4-expert-advisory-system--curation-strategy)
  - [4.1 Pruning Expert Noise](#41-pruning-expert-noise-why-less-is-more)
- [5. Visual Knowledge Graph Overview](#5-visual-knowledge-graph-overview)
  - [5.1 Cost Optimisation Through Graphify](#51-cost-optimisation-through-graphify)
  - [5.2 Graph Statistics](#52-graph-statistics-current)
  - [5.3 Community Breakdown](#53-community-breakdown-top-10-by-node-count)
  - [5.4 God Nodes](#54-god-nodes-most-connected)
  - [5.5 HTML Visualisation](#55-html-visualisation-features)
- [6. Context Hygiene & Optimisation](#6-context-hygiene--optimisation-protocols)
  - [6.1 Session Discipline](#61-session-discipline)
  - [6.2 Graphify Integration](#62-graphify-integration)
- [7. Ecosystem Integrations & Governance](#7-ecosystem-integrations--governance-rules)
  - [7.1 Documentation Strategy](#71-documentation-strategy)
  - [7.2 Jira Integration](#72-jira-integration)
  - [7.3 GitHub MCP Integration](#73-github-mcp-integration)
  - [7.4 AWS Security & OIDC](#74-aws-security--passwordless-oidc)
  - [7.5 Mandatory Git Commit Ticket Prefix](#75-mandatory-git-commit-ticket-prefix)
  - [7.6 Local Development Environment](#76-local-development-environment)
  - [7.7 Custom Commands](#77-custom-commands)
- [8. End-to-End Operational Workflow](#8-end-to-end-operational-workflow)
- [9. How to Adapt This Blueprint](#9-how-to-adapt-this-blueprint)
- [10. Prerequisites](#10-prerequisites)
- [11. Recommended Approach](#11-recommended-approach)
  - [11.1 Sample First Prompt](#111-sample-first-prompt)
- [12. Plugins](#12-plugins)
  - [12.1 Graphify](#121-graphify--knowledge-graph-installed-data-available)
  - [12.2 VibeGuard](#122-vibeguard--secret-redaction-installed-active-at-next-startup)
  - [12.3 DCP](#123-dynamic-context-pruning--dcp-installed-active-at-next-startup)
  - [12.4 Supermemory](#124-supermemory--cross-session-memory-installed-requires-authentication)
  - [12.5 Type Inject](#125-type-inject--typescript-type-context-installed)
   - [12.6 Notificator — REMOVED](#126-notificator--desktop-notifications-removed-2026-07-27)
  - [12.7 Scheduler](#127-scheduler--recurring-agent-jobs-installed)
  - [12.8 Goal Plugin](#128-goal-plugin--session-scoped-goals-installed)

## 1. Introduction & Core Objective

This document outlines the architectural blueprint, design philosophy, and operational guardrails of an enterprise-grade Multi-Agent Software Development System built inside OpenCode. The objective is to transform AI from a basic auto-complete snippet generator into a structured, highly disciplined, and autonomous engineering team capable of planning, executing, auditing, and continuously deploying production code.

Many AI coding setups fail because they treat the AI as a single omniscient developer. In reality, complex software engineering requires distinct division of labour, domain specialisation, rigorous governance, and automated verification. This framework establishes an interconnected ecosystem of sub-agents and expert advisory personas that mirror a high-performing human software organisation while maintaining strict human-in-the-loop safety controls.

**Core Philosophy:** The goal is not to eliminate human oversight, but to elevate human engineers from manual coders to strategic orchestrators — spending minutes reviewing pre-tested, fully compliant Pull Requests instead of hours writing baseline code.

---

## 2. Architectural Foundations & Delivery Paradigms

To avoid common pitfalls — scope creep, architectural drift, monolithic pull requests, and broken deployment pipelines — the system is governed by four non-negotiable delivery paradigms.

### 2.1 Walking Skeleton First (Story #1)

Story #1 of any new initiative is explicitly designated to build a minimal end-to-end slice: compiling code, running a passing test, building via CI/CD, and deploying a lightweight health-check endpoint to production. This establishes the delivery pipeline before any business logic is written, reducing integration risk from day one.

### 2.2 Trunk-Based Development over GitFlow

AI sub-agents perform best when feedback loops are extremely tight. All agent work is conducted on short-lived topic branches that merge directly back into `main` via small, frequent Pull Requests. Long-lived feature branches are strictly prohibited, avoiding merge conflicts, drift, and context staleness.

### 2.3 Continuous Deployment (Deploy Every Passing Commit)

Every commit merged to `main` automatically triggers the full testing suite. If unit, API, static analysis, and security checks pass, the CI/CD pipeline immediately executes a zero-downtime deployment to production.

### 2.4 Decoupling Deployment from Release (Feature Flags)

Incomplete user stories must never expose unready capabilities to end users. All incomplete features are wrapped in feature flags defaulting to `OFF` in production. This allows continuous deployment of passing code while granting the Product Owner complete control over when a feature is activated.

---

## 3. Multi-Agent Architecture & Multi-Model Allocation Strategy

### 3.1 Defence-in-Depth Model Allocation

To eliminate systematic blind spots, authoring agents (who write code and infrastructure) and auditing agents (who review and check security) run on distinct model families or reasoning architectures. This prevents auditors from inheriting the exact same training biases, logic gaps, or hallucinations as the authors.

### 3.2 Agent Model Matrix

| Agent | Primary Role | Model | Family | Role | Temp |
|---|---|---|---|---|---|
| @team-member-product-owner | Requirement Discovery & BDD Criteria | `opencode/claude-sonnet-4-6` | Anthropic | Author | 0.3 |
| @team-member-tech-lead | Planner & Sub-Agent Dispatcher | `opencode/claude-opus-5` | Anthropic | Planner | 0.1 |
| @team-member-devops-engineer | Terraform, CDK & CI/CD | `opencode/gpt-5.3-codex` | OpenAI | Author | 0.1 |
| @team-member-architecture-guardian | C4 Models, Domain Boundaries & ADRs | `opencode/claude-opus-5` | Anthropic | Audit | 0.2 |
| @team-member-db-designer | Schemas, DDL Migrations & Queries | `opencode/gpt-5.3-codex` | OpenAI | Author | 0.1 |
| @team-member-ui-builder | Frontend & WCAG 2.2 AA Standards | `opencode/gpt-5.3-codex` | OpenAI | Author | 0.3 |
| @team-member-tester-unit-and-quality | Unit Tests, Coverage & Mutation Testing | `opencode/gpt-5.3-codex` | OpenAI | Author | 0.1 |
| @team-member-tester-api | API Contract & Payload Verification | `opencode/gpt-5.3-codex` | OpenAI | Author | 0.1 |
| @team-member-security-auditor (Audit) | Cybersecurity Audit & OWASP Top 10 | `opencode/claude-opus-5` | Anthropic | Audit | 0.0 |
| @team-member-code-reviewer (Audit) | Static Code Review & SOLID Compliance | `opencode/claude-sonnet-4-6` | Anthropic | Audit | 0.1 |
| @team-member-performance-engineer | Load testing, k6, latency/throughput SLOs | `opencode/gpt-5.3-codex` | OpenAI | Author | 0.2 |
| **Expert Advisors** | | | | | |
| @expert-alex-xu | Distributed Systems & System Design | `opencode/claude-opus-5` | Anthropic | Advisory | 0.2 |
| @expert-dave-farley | Continuous Delivery & TDD | `opencode/claude-sonnet-4-6` | Anthropic | Advisory | 0.1 |
| @expert-kent-beck | TDD & XP | `opencode/claude-sonnet-4-6` | Anthropic | Advisory | 0.2 |
| @expert-uncle-bod | SOLID & Clean Architecture | `opencode/claude-opus-5` | Anthropic | Advisory | 0.2 |

> **Model References:** The `Model` column is the exact value of each agent's `model:` frontmatter field in `.opencode/agents/*.md`, which is the single source of truth for model allocation. Zen model IDs are fully qualified as `opencode/<model-id>` — see §3.4. Note that Zen uses **dashes** in Claude version numbers (`claude-sonnet-4-6`) but **dots** for OpenAI and Google (`gpt-5.3-codex`, `gemini-3.5-flash-lite`); an unqualified or mis-punctuated ID fails silently at invoke time.

> **Separation Invariant:** Every agent that authors code or infrastructure runs on OpenAI (`gpt-5.3-codex`); every agent that audits it runs on Anthropic (`claude-opus-5` / `claude-sonnet-4-6`). No artefact is therefore reviewed by the same model family that produced it, satisfying §3.1 without exception. @team-member-product-owner is the one Anthropic-hosted "Author", but it authors requirements rather than code and sits outside the review path, so it does not weaken the invariant. **When reassigning any model, re-check this table: moving an author onto Anthropic or an auditor onto OpenAI silently collapses the guarantee.**

> **Security Note:** The Security Auditor agent is configured with a temperature of **0.0** — the lowest possible value. This is intentional: security auditing must prioritise deterministic, repeatable analysis over creative variation. Any hallucination in a security audit could introduce undetected vulnerabilities, so the system guarantees maximum rigour by eliminating output randomness.

### 3.3 Agent Responsibilities

**@team-member-product-owner** — Drives requirement discovery, challenges premature technical solutions, writes INVEST stories with BDD Gherkin criteria, mandates Walking Skeleton, manages feature flag release status, and tracks defects.

**@team-member-tech-lead** — Acts as system planner and engineering dispatcher. Advises on technical trade-offs, coordinates sub-agent execution pipelines, enforces Trunk-Based rules, and maintains architectural integrity.

**@team-member-devops-engineer** — Generates Infrastructure-as-Code (Terraform / AWS CDK), constructs CI/CD workflows, configures cloud OIDC authentication, and manages continuous deployment pipelines.

**@team-member-architecture-guardian** — Maintains C4/arc42 architectural models, enforces domain boundaries, reviews system design, and documents Architecture Decision Records (ADRs).

**@team-member-db-designer** — Designs relational and document schemas, writes migration scripts, optimises query performance with execution plan verification, and manages index strategies.

**@team-member-ui-builder** — Implements user interfaces conforming to accessibility standards (WCAG 2.2 AA / GOV.UK Design System) and wraps UI components in feature flags.

**@team-member-tester-unit-and-quality** — Writes fast, isolated unit tests with high branch coverage prior to PR creation.

**@team-member-tester-api** — Verifies REST/GraphQL API contracts, end-to-end payload validations, and integration boundary tests.

**@team-member-security-auditor** — Audits code and IaC for vulnerability patterns, OWASP Top 10 risks, plaintext secrets, and aggressive IAM wildcards.

**@team-member-code-reviewer** — Performs static code reviews for readability, SOLID compliance, error handling, and maintainability before human review.

### 3.4 Provider Architecture

OpenCode routes all LLM requests through **OpenCode Zen**, a curated multi-vendor AI gateway operated by the OpenCode team. Zen is a **built-in provider** — it requires **no** `provider` block in `opencode.json`. Declaring a custom provider for Zen (e.g. a fabricated `@ai-sdk/zen` npm package) breaks model resolution.

#### Connection Details

| Property | Value |
|---|---|
| Provider ID | `opencode` |
| Model reference format | `opencode/<model-id>` |
| Endpoint (Anthropic family) | `https://opencode.ai/zen/v1/messages` — `@ai-sdk/anthropic` |
| Endpoint (OpenAI family) | `https://opencode.ai/zen/v1/responses` — `@ai-sdk/openai` |
| Endpoint (Google family) | `https://opencode.ai/zen/v1/models/<model-id>` — `@ai-sdk/google` |
| Model catalogue | `https://opencode.ai/zen/v1/models` (authoritative, live) |
| Auth | Zen API key from https://opencode.ai/auth, registered via `/connect` in the TUI |
| Credential store | `~/.local/share/opencode/auth.json` under key `opencode` — **not** an env var, never in `.env` |

Zen is billed pay-as-you-go per request against workspace credits. Endpoint and SDK package are selected automatically per model family; the table above documents them for out-of-band API use only.

#### Model Resolution

Agents reference models directly by fully qualified Zen ID in their `model:` frontmatter. There is no alias indirection layer:

```yaml
---
description: Audits code for security, performance and Clean Code standards
mode: subagent
model: opencode/claude-sonnet-4-6
temperature: 0.1
---
```

Defaults are set in `.opencode/opencode.json`:

```json
"model": "opencode/claude-opus-5",
"small_model": "opencode/gemini-3.5-flash-lite"
```

- `model` — default for primary agents and any subagent that omits `model:`.
- `small_model` — used for session titles and summaries. Pointing this at a cheap model avoids spending flagship-tier tokens on housekeeping.

#### Allocated Models

Three models cover the whole team. All are non-deprecated as of 2026-07-27; prices are USD per 1M tokens (input / output).

| Model ID | Vendor | Price | Allocated To |
|---|---|---|---|
| `opencode/claude-opus-5` | Anthropic | $5.00 / $25.00 | Tech Lead, Architecture Guardian, Security Auditor, Alex Xu, Uncle Bob |
| `opencode/claude-sonnet-4-6` | Anthropic | $3.00 / $15.00 | Product Owner, Code Reviewer, Dave Farley, Kent Beck |
| `opencode/gpt-5.3-codex` | OpenAI | $1.75 / $14.00 | DevOps, DB Designer, UI Builder, both Testers, Performance Engineer |
| `opencode/gemini-3.5-flash-lite` | Google | $0.30 / $2.50 | `small_model` — titles and summaries only |

#### Deprecation Watch

Zen retires models on published dates (see the Deprecated models table at https://opencode.ai/docs/zen). Retired IDs stay listed in the catalogue for a period but must not be used. Already retired and explicitly avoided here:

- `gpt-5.2-codex`, `gpt-5.1-codex`, `gpt-5.1-codex-max`, `gpt-5.1-codex-mini`, `gpt-5-codex` — retired 2026-07-23
- `claude-sonnet-4` — retired 2026-06-15; `claude-opus-4-1` — retires 2026-08-05

Re-check this list before changing any agent's model.

---

## 4. Expert Advisory System & Curation Strategy

When the Product Owner or Tech Lead faces complex trade-offs (e.g., relational vs. document database), the system consults specialised expert profiles to present an objective trade-off matrix.

> **Persona Creation:** These expert profiles were not manually written. An AI analysed hundreds of hours of public content — YouTube talks, conference presentations, published books, and technical courses — from each individual. This content was synthesised into a persona that captures their core principles, decision-making frameworks, and typical advice patterns. When consulted, the personas respond in a manner the real person likely would. They are not real, but the sheer volume of public material makes them feel remarkably authentic.

### 4.1 Pruning Expert Noise (Why Less is More)

Early iterations included dozens of expert profiles from YouTube educators, specific course creators, and niche authors. This created significant context noise, prompt dilution, and conflicting advice. After strict curation, the system consolidated down to **four industry-standard pillars**:

1. **Uncle Bob (Robert C. Martin)** — Author of *Clean Code* and SOLID principles. Consulted for domain decoupling, object-oriented design, and maintainability.
2. **Dave Farley** — Author of *Continuous Delivery*. Consulted for Trunk-Based Development rules, pipeline automation, and deployment safety.
3. **Kent Beck** — Creator of Extreme Programming and TDD. Consulted for test isolation, refactoring strategies, and unit test design.
4. **Alex Xu** — Author of *System Design Interview*. Consulted for high-level architecture trade-offs, scaling patterns, and database selection.

---

## 5. Visual Knowledge Graph Overview

The system architecture and agent relationships are captured in an interactive knowledge graph generated by graphify, providing a navigable map of the entire codebase and configuration.

![Knowledge Graph](graph-screenshot.png)

> *Interactive version: open `graphify-out/graph.html` in a browser.*

> **📅 Graph data snapshot:** This graph and all associated metrics (§5.1–§5.5) are accurate as of commit `d2c81212` (2026-07-26). The graph is a static snapshot — run `graphify update .` from the project root to regenerate with current code.

### 5.1 Cost Optimisation Through Graphify

graphify reduces token consumption and drives down operational costs by replacing expensive LLM re-reading of source files with cheap, deterministic local computation. Graphify's creator (Safi Shamsi) reports a 71.5× token reduction (~98.6% reduction) — distilling a typical 100,000-token codebase into roughly 1,400 tokens of graph structure. By injecting far less content into every prompt, the AI takes substantially longer to hallucinate, producing more reliable and focused reasoning, and a massive cost reduction on token usage.

- **AST Extraction is Free**: Code structure — classes, functions, imports, dependencies — is parsed locally using tree-sitter parsers. This runs at zero token cost, producing structured nodes and edges without any LLM call.
- **Cached Semantic Extraction**: Once entities and relationships are extracted from documentation or images, the results are cached on disk. Incremental updates (`graphify update .`) only re-process changed files, avoiding redundant API calls.
- **Subgraph Queries Over Full Files**: When an agent needs to understand a specific part of the system, it queries the graph for a scoped subgraph instead of loading every source file into context. This dramatically reduces the token footprint per session.
- **Community-Directed Navigation**: Community detection groups related code into clusters. Agents can jump directly to the relevant community rather than scanning the entire codebase, keeping context windows small and focused.

The result: agents spend tokens on reasoning and code generation, not on re-discovering what the graph already knows.

### 5.2 Graph Statistics (Current)

| Metric | Value |
|---|---|---|
| Total Nodes | 486 |
| Total Edges | 458 |
| Communities | 53 (49 shown, 4 thin omitted) |
| Corpus | 63 files (~26,854 words) |
| Extraction | 100% EXTRACTED (0% inferred) |
| Token Cost | 0 input · 0 output (code-only, no LLM round-trip) |
| Source Commit | `d2c81212` |
| **Last Updated** | 2026-07-26 |

### 5.3 Community Breakdown (Top 10 by Node Count)

| Community | Nodes | Description |
|---|---|---|
| OpenCode Autonomous Engineering System Blueprint | 34 | Entire blueprint document — §1–§12: intro, foundations, agent architecture, plugins, workflow |
| Local Development Guide | 29 | Setup guide, env vars, Maven Wrapper, JDK 21 install, project structure, doc references |
| 3. Multi-Agent Architecture & Multi-Model Allocation Strategy | 29 | Agent model matrix, model ID mappings, provider config, agent responsibilities |
| opencode-setup.md | 26 | AGENTS.md — Clean Architecture, Security by Design, project conventions, ADR references |
| opencode (Zen) | 24 | Provider connectivity — Zen endpoints, auth, model allocation, ADR refs |
| Non-Negotiable Rules | 19 | Defect tracking, DoD, deployment strategy, Gherkin BDD, acceptance criteria |
| Local Development Guide | 19 | DB migrations, changelog conventions, environment variables, dev workflow |
| atlassian | 18 | Jira MCP server config, env vars, changelog, versioning, ADR references |
| atlassian | 18 | Jira server config — command, environment, token mappings |
| 7. Ecosystem Integrations & Governance Rules | 14 | Documentation strategy, Jira, GitHub MCP, AWS OIDC, direnv, CI/CD pipeline |

### 5.4 God Nodes (Most Connected)

1. `instructions` — 16 edges
2. Product Owner / Business Analyst Agent — 12 edges
3. OpenCode Autonomous Engineering System Blueprint — 12 edges
4. 11. Plugins — 10 edges
5. StrideCategoryTest — 9 edges
6. Performance Testing Conventions — k6 + InfluxDB + Grafana — 9 edges
7. Local Development Guide — 9 edges
8. models — 8 edges
9. plugin — 7 edges
10. StrideCategory — 7 edges

### 5.5 HTML Visualisation Features

The interactive graph (`graph.html`) includes:
- **XSS Prevention** — safe HTML escaping with `data-nid` attributes and document-level event delegation
- **Hyperedge Visualisation** — shaded region hulls with centroid labels for hyperedge groups
- **Interactive UI** — dynamic community filtering, select-all / indeterminate toggle, automatic node focus, and detail panels

---

## 6. Context Hygiene & Optimisation Protocols

### 6.1 Session Discipline

- **One Session Per User Story**: Each Jira story is executed in a fresh OpenCode session (`/new`). This prevents context pollution and cross-story contamination, reducing the risk of AI hallucination and keeping response quality consistently high.
- **Context Compaction**: For long sessions, run `/compact` to compress verbose output.

### 6.2 Graphify Integration

- **AST Parsing**: Uses local tree-sitter parsers at zero-token context cost.
- **Output Files**: Stores assets in `graphify-out/` (`graph.json`, `GRAPH_REPORT.md`, `graph.html`).
- **Automated Updates**: Git post-commit hook (`graphify hook install`) rebuilds the AST graph on every commit, ensuring the AI always has a fresh knowledge graph up to date for any new task.

---

## 7. Ecosystem Integrations & Governance Rules

### 7.1 Documentation Strategy

All system documentation, architectural decision records (ADRs), and living guides are maintained directly within GitHub — repository READMEs, markdown files in `docs/`, and GitHub Wiki/Pages — ensuring documentation stays version-controlled alongside code.

### 7.2 Jira Integration

Task tracking is integrated via the Atlassian MCP plugin:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "atlassian": {
      "type": "local",
      "command": ["uvx", "mcp-atlassian"],
      "enabled": true,
      "environment": {
        "JIRA_URL": "https://your-domain.atlassian.net",
        "JIRA_USERNAME": "opencode-bot@yourdomain.com",
        "JIRA_API_TOKEN": "{env:JIRA_API_TOKEN}"
      }
    }
  }
}
```

#### Credential Setup (read this before minting a token)

> **The API token must be created while signed in as the bot account itself — not as your administrator or personal account.** Atlassian Basic auth pairs `JIRA_USERNAME` with the token, and the token is only valid for the account that minted it. An administrator token will not work for the bot's email, no matter how much authority that administrator has. This is the single most common way to get the integration wrong, and it cost real time here.

The failure is nastier than a plain rejection, because the mismatch does not announce itself:

- `GET /rest/api/3/myself` returns **401**, but that endpoint is not what the tools call.
- Ordinary reads such as `GET /rest/api/3/project/search` return **HTTP 200 with `total: 0`** — Jira silently falls back to *anonymous* access rather than refusing.

So a wrong-owner token looks exactly like an empty or non-existent project. You will be told "no project could be found with key THREAT" and conclude the project is missing, when in fact you were never authenticated. Always confirm identity explicitly before diagnosing anything else:

```bash
set -a; . ./.env; set +a
curl -s -u "$JIRA_USERNAME:$JIRA_API_TOKEN" \
  -H "Accept: application/json" "$JIRA_URL/rest/api/3/myself" | jq '.accountId, .displayName, .emailAddress'
```

This must return the **bot's** account. If it returns your own name, the token belongs to you and the integration is misconfigured.

Two further operational notes:

- **Quote the credential inline.** Building `AUTH="-u $USER:$TOKEN"` and then running `curl $AUTH ...` sends the request *unauthenticated* — producing the same misleading `total: 0`. Always write `-u "$JIRA_USERNAME:$JIRA_API_TOKEN"` directly on the command.
- **Restart OpenCode after changing `.env`.** `opencode.json` resolves `{env:JIRA_API_TOKEN}` when it spawns the `uvx mcp-atlassian` subprocess, so the value is baked in at start-up. Editing `.env` in place has no effect on a running session, and the MCP tools will keep using the old credential while your shell uses the new one.

#### Jira Protection & Defect Lifecycle

- **Dedicated Bot User**: OpenCode operates under a dedicated Jira service user with permissions restricted to Browse, Create, Edit, and Transition issues.
- **Revoked Delete Rights**: Delete Issues, Delete Comments, and Delete Attachments permissions are explicitly revoked. Any delete attempt returns 403 Forbidden. Cleanup of test or obsolete tickets is therefore a human action in the Jira UI — deliberately, so the agents cannot destroy tracker history.
- **Reporter Cannot Be Spoofed**: The bot lacks the Modify Reporter permission, so every issue it raises is unambiguously attributed to the bot. This is what makes the service account worth the setup cost over reusing a personal token.
- **Rejection Workflow**: Obsolete stories receive an explanatory comment, a "Reject" transition, and resolution set to "Won't Do."
- **Defect Tracking**: Pre-deployment defects are logged as Bug Sub-tasks under the parent User Story (blocking merge). Post-deployment defects are standalone Bug Issues linked via "caused by" for defect rate metrics.

#### Project Shape Constraints

The target project is **team-managed** (`style: next-gen`), which changes the available fields in ways that break otherwise-correct tool calls:

- **There is no `Components` field.** Passing `components` to `jira_create_issue` fails. Team-managed projects drop it entirely.
- **Epics are linked through `Parent`**, not the classic company-managed Epic Link custom field.
- **Story points are `Story point estimate`.**
- Issue types are `Epic`, `Subtask`, `Task`, `Story` — a Story requires only `project`, `issuetype` and `summary`.

Confirm the shape rather than assuming it, since a company-managed project would behave differently:

```bash
curl -s -u "$JIRA_USERNAME:$JIRA_API_TOKEN" \
  "$JIRA_URL/rest/api/3/project/$JIRA_PROJECT_KEY" | jq '.style, .projectTypeKey'
```

**Description formatting survives intact.** Markdown sent to `jira_create_issue` is stored as proper ADF: fenced ```` ```gherkin ```` blocks keep their language attribute, and `- [ ]` items become real interactive Jira checkboxes rather than plain bullets. The Product Owner's story template — Gherkin acceptance criteria plus a Definition of Done checklist — therefore renders correctly and needs no downgrading.

> Note that `mcp-atlassian` echoes back a **wiki-markup** rendering of what you sent, which looks lossy (`{noformat}` blocks, bullets instead of checkboxes). That echo is not what Jira persisted. Verify against the stored ADF via `GET /rest/api/3/issue/<KEY>?fields=description` before concluding anything was lost — an agent reading only the echo will report false corruption.

#### Agent-Level Jira Permissions (client-side layer)

The controls above are enforced by Jira itself and apply to *every* agent equally, because all agents share the one bot credential. A second, client-side layer in OpenCode decides **which agents may even attempt** a given operation. Both layers are required: Jira alone cannot distinguish the Product Owner from the Performance Engineer.

Rules live in the `permission` block of `.opencode/opencode.json` (global default) and in `permission:` frontmatter of individual `.opencode/agents/*.md` files (per-agent override). Agent rules take precedence over global ones.

Three profiles are in force across the 15 agents:

| Profile | Agents | Jira reads | Jira writes |
|---|---|---|---|
| **Write-capable** | `product-owner`, `tech-lead` | allow | **ask** (human confirms each) |
| **Read-only** | the 9 delivery agents — architecture-guardian, code-reviewer, db-designer, devops-engineer, performance-engineer, security-auditor, tester-api, tester-unit-and-quality, ui-builder | allow | **deny** |
| **No access** | the 4 expert advisers — alex-xu, dave-farley, kent-beck, uncle-bod | deny | deny |

Rationale: the backlog is a shared source of truth, so *narrating* work into it is a product decision, not an engineering one. Delivery agents read tickets freely but cannot alter them; advisory experts have no business touching a tracker at all. Two write-capable agents keeps accountability legible.

> **`deny` and `ask` are not the same mechanism.** `deny` removes the tool from the model's toolset entirely — the agent cannot see or name it, and no request ever reaches Jira. `ask` keeps the tool and gates each individual call on human approval. Only `deny` is a hard guarantee: `opencode --auto` auto-approves everything that is not explicitly denied.

##### Maintaining the rules

Keys are glob patterns (`*` = zero or more characters) matched against tool names, and **the last matching rule wins** — so the broad catch-all goes first and exceptions come after. Two traps, both of which bit us during implementation:

- **Exact names silently under-match.** `atlassian_jira_move_issue` does not cover `atlassian_jira_move_issues_to_backlog`, which fell through to the `allow` catch-all — a real write leak. Prefer `atlassian_jira_move_*`. Note that `*_delete_issue` and `*_move_issue` are still pinned to `deny` *after* the wildcard, so destructive moves stay hard-blocked.
- **Broad patterns over-match reads.** `atlassian_jira_batch_*` wrongly caught the read-only `atlassian_jira_batch_get_changelogs`, which now carries an explicit `allow` after it.

Neither trap is visible by inspection. When adding rules, enumerate every `atlassian_jira_*` tool, resolve each against the rule list with last-match-wins semantics, and confirm that reads and writes land where intended. Verify at runtime with a fresh `opencode run` process — permission config is read at process start, so an already-running session will not pick up changes.

### 7.3 GitHub MCP Integration

Repository, issue, pull request and Actions context is read through GitHub's **official remote MCP server**, configured alongside Atlassian in `opencode.json`:

```json
"github": {
  "type": "remote",
  "url": "https://api.githubcopilot.com/mcp/",
  "enabled": true,
  "headers": {
    "Authorization": "Bearer {env:GITHUB_TOKEN}",
    "X-MCP-Readonly": "true",
    "X-MCP-Toolsets": "repos,issues,pull_requests,actions"
  },
  "oauth": false,
  "timeout": 15000
}
```

> **This server is read-only by design.** All GitHub *writes* — branches, commits, pushes, PR creation and merges — go through the `gh` CLI via `bash`, not through MCP. That keeps one audited path for mutations instead of two, and means a misconfigured toolset cannot silently grant merge rights.

#### Why this shape

- **Remote, not local.** The previously documented local server did not exist. `@modelcontextprotocol/github` was never a real package, and `uvx` is the Python runner, so it could not have launched an npm package under any name. The obvious repair is also wrong: `@modelcontextprotocol/server-github` was deprecated on 2025-04-08 with "package no longer supported", development having moved to `github/github-mcp-server`. The remote server is the maintained path and needs no Docker image or cold start.
- **`X-MCP-Readonly: true`** restricts the exposed tools to reads.
- **`X-MCP-Toolsets`** is deliberately narrow. The full server exposes 100+ tools across ~20 toolsets; loading `all` would consume a large share of every agent's context for capability nobody uses. Four toolsets cover the actual need. Note that unknown *toolset* names are silently ignored, whereas an invalid name in the alternative `X-MCP-Tools` header prevents the server from starting.
- **`oauth: false`** disables OpenCode's OAuth auto-detection. Authentication is the PAT in `GITHUB_TOKEN`; without this the client may attempt a dynamic-registration flow that was never configured.
- **`timeout: 15000`** overrides the 5 000 ms default, which is tight for a first remote handshake.

#### Agent-Level GitHub Permissions

Read-only at the server is the primary control; the permission rules are defence in depth. If a future toolset change or insiders flag reintroduces write tools, they would otherwise arrive pre-approved under the permissive default.

| Profile | Agents | GitHub access |
|---|---|---|
| Experts | alex-xu, dave-farley, kent-beck, uncle-bob | `github_*: deny` — no repository access at all |
| Everyone else | the 9 delivery agents, Product Owner, Tech Lead | reads allowed; write verbs denied |

Denied write patterns in the global block: `github_create_*`, `github_update_*`, `github_delete_*`, `github_merge_*`, `github_push_*`, `github_add_*`, `github_fork_*`, `github_request_copilot_review`. The same last-match-wins glob semantics and the same two traps described in §7.2 apply here.

#### GitHub Protection

**Enabled on `main`** (verified against the live API, not aspirational):

- **Pull requests required.** Direct pushes to `main` are rejected. `enforce_admins` is **true**, so the rule binds repository administrators and the agent token as well — without that, protection would not restrain the agents at all, since they authenticate with an `admin: true` credential.
- **Green CI required.** The `build` status check must pass, in strict mode, so a branch must be up to date with `main` before merging.
- **Force pushes and branch deletions blocked** for everyone.
- **Approvals required: 0.** GitHub does not permit approving your own pull request, so on a single-maintainer repository any non-zero requirement would make every PR permanently unmergeable. The maintainer self-merges once `build` is green.

> **Known gap — token scope.** Authentication currently uses a **classic** PAT (`ghp_`) with `repo`, `project` and `write:org`, which grants `admin: true` on this repository and full read/write across *all* the owner's repositories. Because it holds admin rights it can also edit the protection rules above; branch protection therefore converts a silent direct push into a deliberate, auditable act rather than an absolute boundary. Closing this properly means a **fine-grained PAT scoped to this repository with Administration: No Access**, which pairs naturally with the pending rotation of `GITHUB_TOKEN`. Until then, do not describe the token as least-privilege.

### 7.4 AWS Security & Passwordless OIDC

- **Zero Static Credentials**: No long-lived AWS Access Keys are stored in GitHub Secrets or the repository.
- **Short-Lived OIDC Tokens**: GitHub Actions authenticates to AWS using OpenID Connect to assume temporary IAM roles that expire automatically after pipeline execution.
- **Scoped IAM Roles**: Production IAM roles receive minimum required provisioning rights, with explicit deny guards on destructive operations (e.g., `s3:DeleteBucket`, `rds:DeleteDBInstance`).

### 7.5 Mandatory Git Commit Ticket Prefix

Every commit generated by any agent MUST be prefixed with the active Jira ticket key:

```sh
#!/bin/sh
JIRA_REGEX="([A-Z]{2,10}-[0-9]+)"
if ! grep -qE "$JIRA_REGEX" "$1"; then
  echo "ERROR: Commit rejected! Message must include a valid Jira key (e.g., [THREAT-123] feat: ...)"
  exit 1
fi
```

### 7.6 Local Development Environment

#### Environment Variables via direnv

Sensitive credentials (API keys, tokens) are never committed to the repository. The project uses [direnv](https://direnv.net/) to auto-load environment variables from a gitignored `.env` file when entering the project directory.

**Setup on a new clone:**

```bash
# 1. Install direnv (macOS)
brew install direnv

# 2. Add to ~/.zshrc
echo 'eval "$(direnv hook zsh)"' >> ~/.zshrc
source ~/.zshrc

# 3. Copy and populate the env file
cp .env.example .env   # if an example exists, or create manually
# Edit .env with your credentials:
#   JIRA_URL=...
#   JIRA_API_TOKEN=...
# NOTE: the Zen API key does NOT go here — register it with `/connect` in the
# TUI; OpenCode stores it in ~/.local/share/opencode/auth.json.
# NOTE: the Jira token must be minted while signed in AS THE BOT ACCOUNT, not
# as an administrator. It must match JIRA_USERNAME or Jira falls back to
# anonymous access and reads return an empty result set. See §7.2.

# 4. Allow direnv for this project
direnv allow
```

**How it works:**

- `.envrc` (tracked in git) contains only `dotenv` — a one-line directive telling direnv to load `.env`.
- `.env` (gitignored) holds all secrets.
- Every time you `cd` into the project directory, direnv automatically exports the variables into your shell.
- No manual `export` commands are needed.

#### Required Environment Variables

| Variable | Purpose |
|---|---|
| `JIRA_URL` | Atlassian instance URL |
| `JIRA_USERNAME` | Jira **bot** user email — must be the account that owns the token |
| `JIRA_API_TOKEN` | Jira API token, **minted while signed in as the bot**, not as an administrator (§7.2) |
| `JIRA_PROJECT_KEY` | Target project key for ticket creation |
| `GITHUB_TOKEN` | GitHub PAT (repo scope) |

The Zen API key is deliberately absent: it lives in `~/.local/share/opencode/auth.json`, not here.

After changing any of these, **restart OpenCode** — MCP subprocesses resolve `{env:...}` at spawn time, so a running session keeps the old values.

#### Maven Wrapper

The project uses the **Maven Wrapper** (`./mvnw`) for reproducible builds — no global Maven install required:

| Command | Purpose |
|---|---|
| `./mvnw compile` | Fast compile check |
| `./mvnw test` | Run all tests |
| `./mvnw verify` | Full verification with integration tests |
| `./mvnw spring-boot:run` | Start application on port 8080 |

Requires **Java 21+** (Eclipse Temurin recommended).

#### CI/CD Pipeline

Every push/PR to `main` triggers `.github/workflows/ci.yml` — runs `mvn verify` on `ubuntu-latest` with JDK 21 and uploads the built JAR. See [CI/CD Pipeline](../devops/ci-cd-pipeline.md) for details.

#### Rules Directory

The `.opencode/rules/` directory contains reusable instruction snippets that agents can load on demand: clean architecture, git commits, testing standards, and security rules. These complement the base instructions in `opencode.json`.

### 7.7 Custom Commands

The `.opencode/command/` directory provides three ad-hoc multi-agent orchestration commands:

- **`ask-all-experts`** — Triggers all expert sub-agents in parallel and synthesises their responses into a comparison matrix.
- **`ask-all-team-members`** — Triggers all team-member sub-agents in parallel and synthesises their responses.
- **`multi`** — Triggers specific `@agent` mentions from the prompt in parallel and synthesises their responses.

These complement the `/goal` command (see §12.8) for when you want to poll multiple agents at once without setting a persistent goal.

---

## 8. End-to-End Operational Workflow

The full operational sequence demonstrates how a requirement flows from initial prompt to production deployment:

```mermaid
graph TB
    P1["Phase 1: Requirements Discovery<br/>@team-member-product-owner"]
    P2["Phase 2: Backlog & Jira Seeding<br/>@team-member-product-owner"]
    P3["Phase 3: Technical Design & Branching<br/>@team-member-tech-lead"]
    P4["Phase 4: Implementation & Flagging<br/>@team-member-ui-builder"]
    P5["Phase 5: Automated Verification<br/>@team-member-tester-unit-and-quality & @team-member-tester-api"]
    P6["Phase 6: PR, Audit & Human Gate<br/>@team-member-security-auditor & @team-member-code-reviewer"]
    P7["Phase 7: Continuous Deployment<br/>CI/CD via OIDC → AWS"]

    P1 --> P2 --> P3 --> P4 --> P5 --> P6 --> P7
```

**Phase 1 — Requirements Discovery**: Prompter submits a feature request. @team-member-product-owner interacts directly with the human to challenge premature solutionising, clarify business objectives, and refine the requirements. @team-member-product-owner verifies the proposed solution serves the end-user's needs based on today's accessibility and usability standards, including Government Digital Service (GDS) standards where applicable. Only once the request passes these checks and is deemed worthy of building does @team-member-product-owner pass the instruction to @team-member-tech-lead. Story #1 is always designated as the Walking Skeleton.

**Phase 2 — Backlog & Jira Seeding**: @team-member-product-owner creates INVEST stories with Gherkin BDD criteria and feature flag definitions in Jira, signaling @team-member-tech-lead.

**Phase 3 — Technical Design & Branching**: @team-member-tech-lead creates a short-lived topic branch from `main` and dispatches @team-member-architecture-guardian, @team-member-db-designer, and @team-member-devops-engineer to prepare infrastructure and domain models.

**Phase 4 — Implementation & Flagging**: @team-member-ui-builder and core developers write solution logic, wrapping unreleased capabilities in feature flags.

**Phase 5 — Automated Verification**: @team-member-tester-unit-and-quality and @team-member-tester-api run test suites, creating Bug Sub-tasks for any failing checks.

**Phase 6 — PR, Audit & Human Gate**: OpenCode opens a Pull Request. @team-member-security-auditor and @team-member-code-reviewer perform static audits. Automated CI runs linters and tests. A human engineer reviews and approves the PR.

**Phase 7 — Continuous Deployment**: PR merges to `main`. CI assumes the cloud IAM role via OIDC, executes infrastructure-as-code, and deploys to production.

---

## 9. How to Adapt This Blueprint

Teams looking to build a similar system can customise this blueprint with three key adaptations:

- **Cloud Platform**: Swap AWS OIDC roles for GCP Workload Identity Federation or Azure Managed Identities in `@team-member-devops-engineer.md`.
- **Issue Tracker**: Replace Jira API configuration with GitHub Issues or Linear in `@team-member-product-owner.md`.
- **UI Standards**: Customise `@team-member-ui-builder.md` to enforce your company's design system (e.g., Tailwind, Material UI, Salesforce Lightning) instead of GOV.UK standards.

---

## 10. Prerequisites

Before running any prompt, ensure your local environment is set up:

- [ ] **direnv installed** — `brew install direnv` + hook in `~/.zshrc`
- [ ] **`.env` populated** — `JIRA_URL`, `JIRA_USERNAME`, `JIRA_API_TOKEN`, `JIRA_PROJECT_KEY`, `GITHUB_TOKEN`
- [ ] **Zen authenticated** — `/connect` → OpenCode Zen; key present in `~/.local/share/opencode/auth.json`
- [ ] **direnv allowed** — `direnv allow` in the project root (run once per clone)
- [ ] **OpenCode config installed** — `.opencode/opencode.json` and `.opencode/agents/` present
- [ ] **Models verified** — `opencode models | grep '^opencode/'` lists every ID used in `.opencode/agents/*.md` and `.opencode/opencode.json`
- [ ] **Jira identity verified** — `curl -s -u "$JIRA_USERNAME:$JIRA_API_TOKEN" "$JIRA_URL/rest/api/3/myself"` returns the **bot** account, not yours. Jira MCP needs no `/connect`; it is spawned from the `mcp` block in `.opencode/opencode.json` using the `.env` values (§7.2)

See §7.6 for detailed setup instructions and [docs/devops/local-development.md](../devops/local-development.md) for the full guide.

Key ADRs:
- [ADR-002: Spring Boot Walking Skeleton](../adr/ADR-002-spring-boot-bootstrap.md) — documents the Spring Boot 3.4.4, Java 21, and Maven Wrapper decisions
- [ADR-003: GitHub MCP Integration](../adr/ADR-003-github-mcp-integration.md) — documents the GitHub MCP server rationale and configuration
- [ADR-004: API Contract-First](../adr/ADR-004-api-contract-first.md) — documents OpenAPI 3.1, springdoc, and contract-first conventions
- [ADR-005: Error Handling Strategy](../adr/ADR-005-error-handling-strategy.md) — documents RFC 9457 Problem Details and the exception hierarchy
- [ADR-006: Build Quality Gates](../adr/ADR-006-build-quality-gates.md) — documents Checkstyle, SpotBugs, JaCoCo, and Enforcer rules
- [ADR-007: Versioning Strategy](../adr/ADR-007-versioning-strategy.md) — documents SemVer 2.0.0 and Keep a Changelog conventions
- [ADR-008: Database Migration Strategy](../adr/ADR-008-database-migration-liquibase.md) — documents Liquibase with XML changelogs for all schema changes
- [ADR-009: Front-End Technology Stack](../adr/ADR-009-frontend-react-typescript.md) — documents React + TypeScript + Vite + GOV.UK Frontend CSS decision

---

## 11. Recommended Approach

Start with **few details** and let @team-member-product-owner (PO) guide the discovery process:

1. **Open a fresh session** (`/new`) — one story per session
2. **Give a lightweight prompt** — a sentence or two about what you want to build
3. **Let your PO interview you** — they will ask about target audience, scope, constraints
4. **Refine together** — clarify business objectives, end-user needs, and acceptance criteria
5. **Your PO validates** — checks against accessibility and usability standards
6. **Your PO dispatches** — the validated story is handed to the Tech Lead for autonomous implementation with auto-continue and safety limits

### 11.1 Sample First Prompt

**1. Requirements discovery** — prompt your PO:

```
@team-member-product-owner I want to build an Elevation of Privilege (EoP) card
game — a threat modelling exercise based on the STRIDE framework.
The goal is to help development teams learn to identify security
threats in a fun, interactive way. Can you help me define the
requirements and scope for this project?
```

Dumping everything at once overloads context and bypasses the PO validation gate. The PO is your requirements partner, not a passive note-taker. Once validated, the PO hands off to the Tech Lead for autonomous execution.

---

## 12. Plugins

OpenCode supports two plugin types: **local plugin files** (`.js`/`.ts` in `.opencode/plugins/`) and **npm packages** declared in `opencode.json`. All are auto-loaded at startup.

The project uses eight plugins, each serving a distinct architectural concern. Configs live in `.opencode/` (project) or `~/.config/opencode/` (global), with project-level overrides taking priority.

### 12.1 Graphify — Knowledge Graph (installed, data available)

Graphify generates a persistent AST-level knowledge graph of the entire codebase. See §5 for the visual overview and §6.2 for the operational integration.

- **File**: `.opencode/plugins/graphify.js`
- **Hook**: `tool.execute.before` — prepends a knowledge-graph reminder before `bash` calls
- **Config**: None (auto-detects `graphify-out/graph.json`)
- **Update**: `graphify update .` (incremental AST rebuild)

### 12.2 VibeGuard — Secret Redaction (installed, active at next startup)

Redacts configured sensitive strings before requests reach the LLM provider (OpenCode Zen) and restores them after the model responds and before local tool execution. Provider never sees plaintext secrets.

- **Package**: `opencode-vibeguard` (npm)
- **Config**: `.opencode/vibeguard.config.json`
- **Data**: None persisted — operates invisibly on every request
- **Placeholder format**: `__VG_<CATEGORY>_<hash12>__` (HMAC-SHA256, session-random secret, irreversible to provider)

### 12.3 Dynamic Context Pruning — DCP (installed, active at next startup)

Reduces token usage by compressing stale conversation spans, deduplicating repeated tool calls, and pruning errored tool inputs. Preserves protected tools (`task`, `skill`, `todowrite`, etc.) and patterns from compression.

- **Package**: `@tarquinen/opencode-dcp` (npm)
- **Config**: `.opencode/dcp.jsonc` (project overrides); `~/.config/opencode/dcp.jsonc` (global defaults)
- **Data**: Run `/dcp` in the TUI to view stats; `/dcp-compress [focus]` to trigger manually
- **Notable**: 3.8k ★, AGPL-3.0, subagent support enabled via `experimental.allowSubAgents: true`

### 12.4 Supermemory — Cross-Session Memory (installed, requires authentication)

Persists project knowledge, user preferences, and session summaries across OpenCode sessions and even across tools (Claude Code, Codex). Injects relevant memories on first message and auto-saves on keywords ("remember...", "save this").

- **Package**: `opencode-supermemory` (npm)
- **Auth**: `bunx opencode-supermemory@latest login` (browser OAuth); or set `SUPERMEMORY_API_KEY` in `.env`
- **Config**: `~/.config/opencode/supermemory.jsonc`
- **Data**: Run `/supermemory-init` to seed codebase memory; memories accumulate naturally through use
- **Notable**: 1.5k ★, MIT, privacy via `<private>` tags

### 12.5 Type Inject — TypeScript Type Context (installed)

Injects TypeScript type signatures into file reads so the LLM sees type context without manual lookup. Reports type errors on writes. Provides MCP tools: `lookup_type`, `list_types`, `type_check`. Resolves imports up to 4 levels deep.

- **Package**: `@nick-vi/opencode-type-inject` (npm)
- **Config**: None (works with existing `tsconfig.json`)
- **Data**: None persisted — acts on file reads/writes transparently
- **Notable**: TypeScript-only; has zero effect on Java files. Most useful when working on `ui/`.

### 12.6 Notificator — Desktop Notifications (REMOVED 2026-07-27)

**Removed following the 2026-07 security audit.** The plugin sent desktop notifications and sound alerts for OpenCode events by shelling out to OS commands (`osascript`/`afplay` on macOS, `notify-send`/ffmpeg on Linux). That command-execution attack surface was not justified by the notification utility. Deleted: `.opencode/plugins/notificator.js`, `notificator.js.map`, `notificator.jsonc`, `notificator-sounds/`, and the `opencode.json` plugin registration. Do not reinstall without an input-sanitization review.

### 12.7 Scheduler — Recurring Agent Jobs (installed)

Schedules recurring agent tasks using OS-native schedulers (launchd on macOS, systemd on Linux). Jobs run `opencode run` with the project's full MCP configuration. Includes no-overlap guard, optional timeout, and automatic logging.

- **Package**: `opencode-scheduler` (npm)
- **Config**: Jobs stored at `~/.config/opencode/scheduler/scopes/*/jobs/*.json` (auto-managed by `/schedule` command)
- **Data**: Run logs via `job_logs`; supervisord at `~/.config/opencode/scheduler/supervisor.pl`
- **Scheduled job**: `nightly-load-test` — runs daily at 02:00, executes k6 health check against `localhost:8080`, reports SLO breaches
- **Notable**: Requires Perl for the supervisor script. Per-project scoping via working directory. Use the `/schedule` OpenCode command to create jobs.

### 12.8 Goal Plugin — Session-Scoped Goals (installed)

Provides a `/goal` workflow for long-running autonomous sessions. Set a goal, the plugin keeps it in context, auto-continues when idle, and stops when complete, blocked, or a safety limit is hit. Supports evidence-gated completion with optional independent auditor.

- **Package**: `opencode-goal-plugin` (npm)
- **Command**: `/goal` — configured in `opencode.json` under `"command"` with `"agent": "team-member-tech-lead"` for orchestrator-driven execution (defaults: max 10 turns, 15 min duration, 200k tokens)
- **Config**: Plugin-level defaults passed as options array in `opencode.json`
- **State file**: `.opencode/goals/state.json` (chmod 0600; added to `.gitignore`)
- **Notable**: Only one persistence-enabled instance per state file; session forks don't inherit parent goals. Relies on experimental OpenCode hooks (`experimental.chat.system.transform`).
