# Graph Report - Elevation of Privilege - EoP  (2026-07-25)

## Corpus Check
- 27 files · ~7,803 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 50 nodes · 45 edges · 6 communities
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `c9562130`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Non-Negotiable Rules
- GOV.UK Design Principles & Rules
- Code Reviewer Agent
- DevOps & Infrastructure Specialist Agent
- tech-lead.md
- Language-Specific Standards

## God Nodes (most connected - your core abstractions)
1. `Non-Negotiable Rules` - 6 edges
2. `Language-Specific Standards` - 5 edges
3. `GOV.UK Design Principles & Rules` - 5 edges
4. `Code Reviewer Agent` - 4 edges
5. `Database Specialist Agent` - 4 edges
6. `UI Builder Agent` - 4 edges
7. `Universal Review Pillars` - 3 edges
8. `DevOps & Infrastructure Specialist Agent` - 3 edges
9. `Technical Standards & Security Guardrails` - 3 edges
10. `Git Commit Message Protocol` - 2 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities (6 total, 0 thin omitted)

### Community 0 - "Non-Negotiable Rules"
Cohesion: 0.18
Nodes (10): 1. Execution Plan Verification (`EXPLAIN ANALYZE`), 2. Migration Safety & Zero Downtime, 3. Schema & Indexing Standards, 4. Query Security & Efficiency, 5. Document & NoSQL Database Standards (MongoDB / DynamoDB), Core Responsibilities, Database Specialist Agent, Deliverable Format (+2 more)

### Community 1 - "GOV.UK Design Principles & Rules"
Cohesion: 0.20
Nodes (9): 1. High-Level Principles, 2. Typography, Colors & Visual Layout, 3. Forms & Component Patterns, 4. Technical Quality & Frameworks, Git Commit Message Protocol, GOV.UK Design Principles & Rules, Output Expectations, Primary Styling Framework Standard (+1 more)

### Community 2 - "Code Reviewer Agent"
Cohesion: 0.25
Nodes (7): 1. Security & Vulnerability Audit, 2. General Clean Code Rules (Uncle Bob's *Clean Code*), Code Reviewer Agent, Context Optimization Rule (Graphify), Git Commit Message Protocol, Review Output Format, Universal Review Pillars

### Community 3 - "DevOps & Infrastructure Specialist Agent"
Cohesion: 0.25
Nodes (7): 1. AWS & GitHub OIDC Rules, 2. Trunk-Based Deployment Rules, Core Responsibilities, DevOps & Infrastructure Specialist Agent, Git Commit Message Protocol, Standard Continuous Deployment Pipeline (Walking Skeleton & Beyond), Technical Standards & Security Guardrails

### Community 4 - "tech-lead.md"
Cohesion: 0.25
Nodes (7): Context Optimization Rule (Graphify), Core Engineering Principles, Documentation Gate, Execution Pipeline Architecture, Git Commit Message Protocol, Session Hygiene Rule, Tech Lead Orchestrator Agent

### Community 5 - "Language-Specific Standards"
Cohesion: 0.40
Nodes (5): Java / JVM, Language-Specific Standards, Python (PEP 8 & The Zen of Python), Ruby on Rails (Ruby Style Guide & RuboCop), TypeScript & JavaScript (Airbnb / Google TS Style)

## Knowledge Gaps
- **33 isolated node(s):** `1. Security & Vulnerability Audit`, `2. General Clean Code Rules (Uncle Bob's *Clean Code*)`, `Java / JVM`, `TypeScript & JavaScript (Airbnb / Google TS Style)`, `Python (PEP 8 & The Zen of Python)` (+28 more)
  These have ≤1 connection - possible missing edges or undocumented components.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Code Reviewer Agent` connect `Code Reviewer Agent` to `Language-Specific Standards`?**
  _High betweenness centrality (0.043) - this node is a cross-community bridge._
- **Why does `Language-Specific Standards` connect `Language-Specific Standards` to `Code Reviewer Agent`?**
  _High betweenness centrality (0.032) - this node is a cross-community bridge._
- **What connects `1. Security & Vulnerability Audit`, `2. General Clean Code Rules (Uncle Bob's *Clean Code*)`, `Java / JVM` to the rest of the system?**
  _33 weakly-connected nodes found - possible documentation gaps or missing edges._