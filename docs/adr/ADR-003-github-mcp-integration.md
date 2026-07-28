# ADR-003: GitHub MCP Integration

**Status:** Accepted (amended 2026-07-28 — original decision was never executable)  
**Date:** 2026-07-26  
**Deciders:** @team-member-tech-lead  

## Context
The agent system discusses PRs, code reviews, and GitHub Actions but had no native GitHub access. The Atlassian MCP was already configured for Jira, leaving a gap for GitHub interactions.

Two facts emerged later that invalidated the original decision:

1. **The configuration could never have worked.** `@modelcontextprotocol/github` was not a real package, and `uvx` is the Python package runner, so it could not launch an npm package under any name. The server never started and no `github_*` tools were ever exposed. The failure was silent — nothing in the agent system depended on those tool names, so nobody noticed for two days.
2. **The plausible repair is also dead.** `@modelcontextprotocol/server-github`, the package the original decision presumably meant, was deprecated on 2025-04-08 with the message "package no longer supported". Development moved to `github/github-mcp-server`, maintained by GitHub.

The `gh` CLI (2.96.0, authenticated) was already available to every agent through `bash`, which is why the gap had no practical impact and why the replacement can be read-only.

## Decision
Superseding the original `uvx` entry:

- Use GitHub's **official remote MCP server** at `https://api.githubcopilot.com/mcp/` with `type: remote`. No Docker image, no local process, no cold start.
- **Read-only** (`X-MCP-Readonly: true`). All GitHub writes stay with the `gh` CLI via `bash`, so mutations have one audited path rather than two.
- Restrict toolsets to `repos,issues,pull_requests,actions` (`X-MCP-Toolsets`). The full server exposes 100+ tools; loading `all` would spend a large share of every agent's context on unused capability.
- Authenticate with the existing `GITHUB_TOKEN` PAT via an `Authorization: Bearer` header. Set `oauth: false` to stop OpenCode attempting an unconfigured OAuth flow, and `timeout: 15000` because the 5 000 ms default is tight for a remote handshake.
- Ship `github_*` permission rules in the same change: `deny` for the four expert agents, and `deny` on write verbs for everyone else as defence in depth should a future toolset change reintroduce write tools.

## Consequences
- **Positive:** Agents can read repository, issue, PR and CI state — the capability the original ADR claimed but never delivered.
- **Positive:** No new write surface. Read-only at the server plus denied write verbs means adding this server cannot grant merge or push rights.
- **Neutral:** Requires a valid `GITHUB_TOKEN` in `.env`. Restart OpenCode after changing it; MCP config is resolved at process start.
- **Negative:** Depends on a GitHub-hosted endpoint, so it is unavailable offline and cannot serve GitHub Enterprise Server, which does not support remote hosting. If GHES is ever needed, switch to the Docker-based `ghcr.io/github/github-mcp-server` with `GITHUB_HOST` — note that image expects `GITHUB_PERSONAL_ACCESS_TOKEN`, not `GITHUB_TOKEN`.
- **Negative:** The token remains a classic PAT with admin rights on this repository. See the known gap in Blueprint §7.3.

## Related
- [opencode.json](../../.opencode/opencode.json)
- [Blueprint §7.3 GitHub MCP Integration](../../.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md)
- [Local Development Guide](../devops/local-development.md)
