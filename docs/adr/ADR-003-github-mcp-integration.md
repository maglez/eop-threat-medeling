# ADR-003: GitHub MCP Integration

**Status:** Accepted  
**Date:** 2026-07-26  
**Deciders:** @team-member-tech-lead  

## Context
The agent system discusses PRs, code reviews, and GitHub Actions but had no native GitHub access. The Atlassian MCP was already configured for Jira, leaving a gap for GitHub interactions.

## Decision
- Add `@modelcontextprotocol/github` via `uvx` to `opencode.json`
- Auth via `GITHUB_TOKEN` environment variable (GitHub PAT with `repo` scope)
- Configured alongside the existing Atlassian MCP

## Consequences
- **Positive:** Agents can create PRs, comment on reviews, browse repos, manage issues
- **Neutral:** Requires `direnv allow` and a valid `GITHUB_TOKEN` in `.env`
- **Negative:** MCP adds one more process to the agent startup chain

## Related
- [opencode.json](../../.opencode/opencode.json)
- [Local Development Guide](../devops/local-development.md)
