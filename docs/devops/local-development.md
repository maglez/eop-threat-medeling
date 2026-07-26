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
| `DATASOURCE_URL` | For prod | PostgreSQL | JDBC URL (default: `jdbc:h2:mem:eop` for dev) |
| `DATASOURCE_USER` | For prod | PostgreSQL | DB user (default: `sa` for dev) |
| `DATASOURCE_PASSWORD` | For prod | PostgreSQL | DB password (default: empty for dev) |

All vars go in `.env` (gitignored).

## Database

### Development (H2)

The default `application.yml` profile uses an **H2 in-memory database** with zero setup required. Liquibase runs automatically on application startup — changelogs are applied in order.

- H2 console is available at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:eop`, user: `sa`, no password)
- Hibernate `ddl-auto=validate` — the schema is entirely managed by Liquibase

### Production (PostgreSQL)

Activate the `prod` profile to connect to PostgreSQL:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

PostgreSQL connection details are read from `DATASOURCE_URL`, `DATASOURCE_USER`, and `DATASOURCE_PASSWORD` env vars (set in `.env`).

### Adding a migration

1. Create a new file in `src/main/resources/db/changelog/changes/YYYY-MM-DD--<description>.xml`
2. Add one or more `<changeSet>` blocks with `<rollback>` instructions
3. Run `./mvnw spring-boot:run` — Liquibase applies the new changeset automatically
4. To preview the SQL: `./mvnw liquibase:updateSQL`

See `.opencode/rules/database.md` and ADR-008 for full conventions.

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
