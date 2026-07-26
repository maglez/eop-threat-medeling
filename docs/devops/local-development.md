# Local Development Guide

## Prerequisites
- **Java 21** (JDK) — Install via [Homebrew](https://brew.sh): `brew install openjdk@21`
- **direnv** — [install guide](https://direnv.net/docs/installation.html)
- **uv** (optional, for MCP servers)
- **GitHub PAT** with `repo` scope — for MCP integration

## Setup

```bash
# 1. Clone
git clone git@github.com:maglez/eop-threat-medeling.git
cd eop-threat-medeling

# 2. Allow direnv to load .env vars
direnv allow

# 3. Verify env vars loaded
echo $OPENAI_API_KEY

# 4. Build and test
./mvnw compile
./mvnw test

# 5. Run the application
./mvnw spring-boot:run
# Verify: curl http://localhost:8080/health
```

## Environment Variables

| Variable | Required | Source | Purpose |
|---|---|---|---|
| `OPENAI_API_KEY` | Yes | AWS Bedrock Mantle | AI provider auth |
| `OPENAI_BASE_URL` | Yes | AWS Bedrock Mantle | Provider endpoint |
| `JAVA_HOME` | Yes | JDK 21 install | Path to JDK 21 (e.g. `/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`) |
| `GITHUB_TOKEN` | Yes | GitHub PAT | GitHub MCP auth |
| `JIRA_URL` | For Jira | Atlassian | Jira instance URL |
| `JIRA_USERNAME` | For Jira | Atlassian | Jira bot email |
| `JIRA_API_TOKEN` | For Jira | Atlassian | Jira API auth |

All vars go in `.env` (gitignored).

## Common Commands

| Command | Purpose |
|---|---|
| `./mvnw compile` | Fast compile check |
| `./mvnw test` | Run all tests |
| `./mvnw verify` | Full verification (including integration tests) |
| `./mvnw spring-boot:run` | Start application on port 8080 |
| `./mvnw clean` | Clean build artifacts |

## Troubleshooting

- **`direnv: error .envrc is blocked`** — Run `direnv allow`
- **`Error: Missing authorization header`** — Check `OPENAI_API_KEY` in `.env`
- **Java version mismatch** — Run `java --version` and ensure it's 21. Install via Homebrew: `brew install openjdk@21`, set `JAVA_HOME` in `.env`
