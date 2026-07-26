# CI/CD Pipeline

The Walking Skeleton CI pipeline runs on every push/PR to `main`.

## Workflow: `.github/workflows/ci.yml`

```yaml
name: CI
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - run: mvn verify --batch-mode
      - uses: actions/upload-artifact@v4
        with:
          name: app-jar
          path: target/*.jar
```

## What it does

1. **Checkout** the repository
2. **Install JDK 21** (Eclipse Temurin) — matching the project's `java.version`
3. **Run `mvn verify`** — compiles source, runs unit tests, runs integration tests
4. **Upload JAR** as a build artifact for downstream use

## Extending the Pipeline

As the project grows beyond Walking Skeleton, add stages:

| Stage | When | What |
|---|---|---|
| Static analysis | Next sprint | SpotBugs / PMD / Checkstyle |
| Security scan | Before release | OWASP dependency check |
| Deployment | Before release | `aws-actions/configure-aws-credentials` + CDK/Terraform |
| Mutation testing | Before release | PIT mutation coverage |

## Verification

After merge, confirm:
1. GitHub Actions shows green ✅
2. Artifact JAR is downloadable from the workflow run
3. (Future) `curl https://<deploy-url>/health` returns `OK`

## Related
- [ADR-002: Spring Boot Walking Skeleton](../adr/ADR-002-spring-boot-bootstrap.md)
- [Local Development Guide](local-development.md)
