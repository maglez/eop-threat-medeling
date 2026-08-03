# syntax=docker/dockerfile:1
#
# The OCI image is the portability boundary for this project: the same artifact
# runs under Compose on a laptop and on EC2, and would run unchanged on ECS,
# Fargate, App Runner or EKS. Only the thin orchestration layer around it is
# environment-specific. See docs/adr/ADR-012-deployment-target.md.

# ---------------------------------------------------------------------------
# Stage 1 — build the jar from source with the Maven Wrapper.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Dependency resolution gets its own layer, so editing a source file does not
# re-download the dependency tree. Ordering is deliberate: pom first, src last.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY checkstyle.xml ./
COPY src/ src/

# Stop at `package`, not `verify`. CI already gates the tests, SpotBugs and the
# JaCoCo coverage threshold — all three bind to `verify` — and re-running them
# here would both duplicate CI and fail the coverage check, because tests are
# skipped so there is no execution data to measure.
#
# Checkstyle and Enforcer DO still run: both bind to `validate`, which
# `package` includes. A style violation or a banned dependency therefore
# cannot reach a published image.
RUN ./mvnw -B -ntp package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — run it on a JRE, as a non-root user.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Least privilege: a process escape should not begin life as uid 0. The user
# owns nothing writable — the jar stays root-owned and read-only to `app`.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -H -D app

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# busybox `wget` ships with Alpine, so the health check adds no package and no
# attack surface. `curl` would need installing; `HEALTHCHECK` needs neither.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1

USER app

# Exec form, so `java` is PID 1 and receives SIGTERM directly — Spring Boot
# then shuts down gracefully instead of being killed after the grace period.
#
# MaxRAMPercentage makes the JVM read the container memory limit instead of the
# host's. ExitOnOutOfMemoryError turns a heap exhaustion into a container exit
# that the restart policy can act on, rather than a wedged JVM that answers the
# health check just slowly enough to look alive.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "/app/app.jar"]
