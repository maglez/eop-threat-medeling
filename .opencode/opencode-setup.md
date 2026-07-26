Here is the complete, raw Markdown source for the updated **OpenCode Autonomous Engineering System Blueprint**.

You can copy the code block directly into a new file named `OPENCODE_BLUEPRINT.md` in your repository root, or click the **"Download"** button on the rendered code artifact below to save it as an `.md` file.

```markdown
# OpenCode Autonomous Engineering System Blueprint

*Architectural Blueprint, Decision Rationale, Multi-Model Diversity, and Operational Guardrail Protocols*

---

## 1. Introduction & Core Objective
This document outlines the architectural blueprint, design philosophy, and operational guardrails of an enterprise-grade Multi-Agent Software Development System built inside OpenCode[cite: 1]. The objective of this architecture is to transform AI from a basic auto-complete snippet generator into a structured, highly disciplined, and autonomous engineering team capable of planning, executing, auditing, and continuously deploying production code directly to AWS[cite: 1].

Many AI coding setups fail because they treat the AI as a single omniscient developer[cite: 1]. In reality, complex software engineering requires distinct division of labor, domain specialization, rigorous governance, and automated verification[cite: 1]. This framework establishes an interconnected ecosystem of sub-agents and expert advisory personas that mirror a high-performing human software organization while maintaining strict human-in-the-loop safety controls[cite: 1].

> **💡 CORE PHILOSOPHY**  
> The core goal is not to eliminate human oversight, but to elevate human engineers from manual coders to strategic orchestrators—spending 2 minutes reviewing pre-tested, fully compliant Pull Requests instead of 2 hours writing baseline code[cite: 1].

---

## 2. Architectural Foundations & Delivery Paradigms
To avoid scope creep, architectural drift, monolithic pull requests, and broken deployment pipelines, the system is governed by four non-negotiable delivery paradigms[cite: 1]:

* **2.1 Walking Skeleton First (Story #1):** Story #1 of any initiative is explicitly designated to build a minimal end-to-end slice: compiling code, running a passing test, building via GitHub Actions, and deploying a lightweight health-check endpoint directly to AWS production[cite: 1].
* **2.2 Trunk-Based Development over GitFlow:** All agent work is conducted on short-lived topic branches that merge directly back into `main` via small, frequent Pull Requests[cite: 1]. Long-lived feature branches are strictly prohibited[cite: 1].
* **2.3 Continuous Deployment (Deploy Every Passing Commit):** Every commit merged to `main` automatically triggers the full testing suite[cite: 1]. Passing commits immediately execute zero-downtime deployment to AWS[cite: 1].
* **2.4 Decoupling Deployment from Release (Feature Flags):** Incomplete user stories or dark launches are wrapped in Feature Flags (defaulting to `OFF` in production)[cite: 1], granting the Product Owner complete control over activation[cite: 1].

---

## 3. Multi-Agent Architecture & Multi-Model Allocation Strategy

### 3.1 Defense-in-Depth Model Allocation Strategy
To eliminate **systematic blind spots**, authoring agents (who write code/IaC) and auditing agents (who review and check security) run on **distinct model families** or **reasoning architectures**. This prevents auditors from inheriting the exact same training biases, logic gaps, or hallucinations as the authors.


```

[ Primary Generation (Claude / Qwen) ] ──► [ Systems & Infra (GPT-4o / Nemotron) ]
│                                            │
└───────────────► [ Audit Layer ] ◄──────────┘
(DeepSeek-R1 / OpenAI o3-mini)

```

### 3.2 Agent Model Matrix (OpenCode Zen & Free Open-Weights)

| Agent Name | Primary Role | OpenCode Zen Model | Free / Open-Weights Model | Temp | Rationale |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`@product-owner`** | Requirement Discovery & BDD Criteria[cite: 1] | `opencode/claude-3-5-sonnet` | `qwen3:30b` | `0.3` | Nuanced, user-centric breakdown; excels at INVEST stories and Gherkin criteria[cite: 1]. |
| **`@tech-lead`** | Planner & Sub-Agent Dispatcher[cite: 1] | `opencode/claude-3-7-sonnet` | `deepseek-r1` | `0.2` | High-reasoning chain-of-thought needed for orchestration and trunk enforcement[cite: 1]. |
| **`@devops-engineer`** | Terraform, CDK & GitHub Actions[cite: 1] | `opencode/gpt-4o` | `llama3.3-nemotron-super` | `0.1` | Precise declarative syntax adherence; zero hallucinatory flags in cloud IaC[cite: 1]. |
| **`@architecture-guardian`** | C4 Models, Domain Boundaries & ADRs[cite: 1] | `opencode/claude-3-5-sonnet` | `glm-5.1` | `0.2` | Structural design integrity and maintaining Confluence/arc42 documentation[cite: 1]. |
| **`@db-specialist`** | Schemas, DDL Migrations & Queries[cite: 1] | `opencode/gpt-4o` | `qwen3-coder` | `0.1` | Strict SQL syntax execution; prevents breaking migration scripts[cite: 1]. |
| **`@ui-builder`** | Frontend Logic & WCAG 2.2 AA Standards[cite: 1] | `opencode/claude-3-5-sonnet` | `qwen3-coder` | `0.3` | Component generation conforming strictly to design systems and accessibility rules[cite: 1]. |
| **`@unit-tester` / `@api-tester`** | Test Suite Automation & Payload Checks[cite: 1] | `opencode/gpt-4o-mini` | `mistral-small-3.2:24b` | `0.1` | High-speed, deterministic unit test generation and payload verification[cite: 1]. |
| **`@security-auditor`** *(Audit)* | Cybersecurity Audit & OWASP Top 10[cite: 1] | `opencode/o3-mini` | `gpt-oss-120b` | `0.0` | **Cross-Audit Model:** Adversarial logic detection, IAM wildcard checks, secrets audit[cite: 1]. |
| **`@code-reviewer`** *(Audit)* | Static Code Review & SOLID Compliance[cite: 1] | `opencode/deepseek-r1` | `deepseek-r1` | `0.1` | **Cross-Audit Model:** Distinct reasoning architecture checking logic flaws and readability[cite: 1]. |

---

## 4. Expert Advisory System & Curation Strategy
When evaluating complex system trade-offs, sub-agents consult a curated 4-pillar advisory layer[cite: 1]:

1. **Uncle Bob (Robert C. Martin):** Clean Code, SOLID, domain decoupling[cite: 1] (`opencode/claude-3-5-sonnet`, Temp: `0.2`).
2. **Dave Farley:** Continuous Delivery, pipeline safety, trunk rules[cite: 1] (`opencode/gpt-4o`, Temp: `0.1`).
3. **Kent Beck:** Test-Driven Development, test isolation, refactoring[cite: 1] (`opencode/claude-3-5-sonnet`, Temp: `0.2`).
4. **Alex Xu:** System design, database selection, scaling patterns[cite: 1] (`opencode/o3-mini`, Temp: `0.2`).

---

## 5. Context Hygiene, Optimization & Token Reduction Protocols

### 5.1 Session Discipline Rule
To prevent context rot and prompt bloat during extended development:
* **One Session Per User Story:** Each Jira story is executed in a fresh OpenCode session (`/new`). Upon PR merge, git changes persist on disk while conversation noise is discarded.
* **Context Compaction:** For long sessions, run `/compact` to trigger summary compression of verbose tool outputs.

### 5.2 Graphify Context Optimization Integration
To eliminate massive token consumption from agents re-reading raw source files:
* **AST Parsing:** Graphify parses code ASTs locally using `tree-sitter` without LLM token usage.
* **Outputs:** Writes assets to `graphify-out/`: `graph.json` (queryable AST graph), `GRAPH_REPORT.md` (God Node analysis), and `graph.html` (interactive web visualizer).
* **Git Hook Auto-Update:** Enforces `graphify hook install` so `graph.json` is updated incrementally on every git commit.
* **Graph-First Protocol:** `@tech-lead`[cite: 1] and `@architecture-guardian`[cite: 1] must query `graphify` paths first and only inspect source files along the extracted dependency graph.

---

## 6. Ecosystem Integrations & Governance Rules

### 6.1 Atlassian MCP Server Integration
Jira and Confluence are integrated directly via the `mcp-atlassian` server in `opencode.json`:

```json
{
  "$schema": "[https://opencode.ai/config.json](https://opencode.ai/config.json)",
  "mcp": {
    "atlassian": {
      "type": "local",
      "command": ["uvx", "mcp-atlassian"],
      "enabled": true,
      "environment": {
        "JIRA_URL": "[https://your-domain.atlassian.net](https://your-domain.atlassian.net)",
        "JIRA_USERNAME": "opencode-bot@yourdomain.com",
        "JIRA_API_TOKEN": "{env:JIRA_API_TOKEN}",
        "CONFLUENCE_URL": "[https://your-domain.atlassian.net/wiki](https://your-domain.atlassian.net/wiki)",
        "CONFLUENCE_USERNAME": "opencode-bot@yourdomain.com",
        "CONFLUENCE_API_TOKEN": "{env:CONFLUENCE_API_TOKEN}"
      }
    }
  }
}

```

### 6.2 Confluence Documentation Protocol

All system documentation is automatically published to the targeted Confluence Space (`${CONFLUENCE_SPACE_KEY}`):

* **`@product-owner`:** Publishes Product Requirement Documents (PRDs) with linked Jira issues.


* **`@architecture-guardian`:** Publishes C4 system diagrams, arc42 documentation, and Architecture Decision Records (ADRs).


* **`@tech-lead`:** Enforces documentation sync as a mandatory gate before requesting human PR approval.



### 6.3 Mandatory Git Commit Ticket Prefix Rule

Every commit generated by any agent MUST be prefixed with the active Jira ticket key (e.g., `[THREAT-123] feat: ...`).

* **Enforced Failsafe (`.git/hooks/commit-msg`):**
```bash
#!/bin/sh
JIRA_REGEX="([A-Z]{2,10}-[0-9]+)"
if ! grep -qE "$JIRA_REGEX" "$1"; then
  echo "❌ ERROR: Commit rejected! Message must include a valid Jira key (e.g., [THREAT-123] feat: ...)"
  exit 1
fi

```



---

## 7. Custom Endpoints & Cloud Provider Configuration

### 7.1 Private / Self-Hosted GPU Server Setup

For hosting models on local GPU clusters or private inference servers via standard OpenAI-compatible endpoints:

```json
{
  "$schema": "[https://opencode.ai/config.json](https://opencode.ai/config.json)",
  "provider": {
    "my-private-server": {
      "npm": "@ai-sdk/openai-compatible",
      "options": {
        "baseURL": "[http://192.168.1.100:8000/v1](http://192.168.1.100:8000/v1)"
      },
      "models": {
        "deepseek-r1-custom": { "name": "DeepSeek-R1-Local" }
      }
    }
  }
}

```

### 7.2 AWS Bedrock Setup

To route agents to models hosted on Amazon Bedrock using standard AWS SDK credential chains:

```json
{
  "$schema": "[https://opencode.ai/config.json](https://opencode.ai/config.json)",
  "provider": {
    "amazon-bedrock": {
      "options": { "region": "us-east-1" },
      "models": {
        "claude-3-5-sonnet": { "name": "us.anthropic.claude-3-5-sonnet-20241022-v2:0" },
        "claude-3-7-sonnet": { "name": "us.anthropic.claude-3-7-sonnet-20250219-v1:0" }
      }
    }
  }
}

```

---

## 8. Security & Guardrail Protocols

* **Jira Bot Guardrails:** `opencode-bot@yourdomain.com` operates under restricted permissions with `Delete Issues`, `Delete Comments`, and `Delete Attachments` explicitly REVOKED.


* **GitHub Access:** Fine-grained PATs without repo administration rights. Mandatory PR approvals, no direct pushes to `main`, force-pushes permanently disabled.


* **AWS Security:** Zero static credentials stored in code. Continuous Deployment authenticates dynamically via temporary GitHub Actions OpenID Connect (OIDC) roles.



---

## 9. End-to-End Execution Sequence

1. **Requirements Discovery (Plan Mode):** Prompter invokes `@product-owner`. PO challenges premature technical assumptions and scopes Story #1 (Walking Skeleton).


2. **Backlog & Confluence Seeding (Build Mode):** `@product-owner` creates INVEST Jira stories with BDD criteria and publishes PRD to Confluence via MCP.


3. **Branching & Technical Design:** `@tech-lead` opens short-lived topic branch and dispatches `@architecture-guardian` (ADR in Confluence), `@db-specialist`, and `@devops-engineer`.


4. **Implementation & Feature Flagging:** Feature developers build solution logic wrapped in feature flags.


5. **Automated Testing & Commit Prefix:** Unit/API tests run. Commits are prefixed with `[JIRA-KEY]`.


6. **PR, Cross-Model Audit & Human Gate:** OpenCode opens PR. Distinct auditing models (`@security-auditor` on `o3-mini`, `@code-reviewer` on `DeepSeek-R1`) verify quality and security. Human engineer reviews and approves.


7. **Continuous Deployment:** Merges to `main`. GitHub Actions assumes AWS OIDC role, runs Terraform/CDK, and deploys live to production.



```

```