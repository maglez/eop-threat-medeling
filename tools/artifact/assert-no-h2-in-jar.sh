#!/usr/bin/env bash
#
# Asserts the deployable artifact carries no H2.
#
# Why this exists: EOP-27 pinned `spring.h2.console.enabled: false` in both profiles and
# added a classpath tripwire, but all three of those controls guard the AUTOCONFIGURATION
# path. None of them guarded the artifact. `com.h2database:h2` was `runtime`-scoped, so
# h2-2.4.240.jar shipped inside BOOT-INF/lib of the jar that runs on EC2, and with it 13
# classes in org.h2.server.web including JakartaWebServlet -- an unauthenticated SQL
# console that accepts a caller-supplied JDBC URL (the shape of CVE-2021-42392), sitting
# in an artifact whose every deployed configuration runs the `prod` profile against
# PostgreSQL and therefore never needs H2 at all.
#
# EOP-34 excludes it at `repackage`. This script is what stops that exclusion from
# silently regressing.
#
# Why a script and not a test, stated precisely: no SUREFIRE test can do this, because
# surefire binds to `test`, which runs BEFORE `package`, so the repackaged jar does not
# exist yet when the suite runs. That is a hard mechanical constraint. It is NOT true that
# no test could do it -- maven-failsafe-plugin binds `integration-test` and `verify`, both
# AFTER `package`, and is already available at 3.5.6 from the Boot parent, so a failsafe IT
# reading the jar with java.util.zip.ZipFile would work and would run inside `./mvnw
# verify`. That was considered and rejected: this project declares no failsafe execution
# today, so it would become the seventh declared Maven plugin (.opencode/rules/build-quality.md
# enumerates the six -- spring-boot, checkstyle, javadoc, spotbugs, jacoco, enforcer -- and
# ADR-047 treats that count as a thing to hold; ADR-006 governs the gates themselves and does
# not itself cap the count), and tools/supply-chain/audit-plugins.sh already
# establishes the committed-script-invoked-from-CI idiom for a gate of exactly this shape.
# The cost of that choice is real and is recorded in ADR-047: `./mvnw verify` alone does
# not prove this property, so a developer running only Maven locally does not exercise it.
#
# What it checks:
#   1. Exactly one repackaged jar exists         -- so a rename or a build change cannot
#                                                   make the checks below vacuous.
#   2. The PostgreSQL driver IS present          -- a positive control. Without it, an
#                                                   empty or truncated jar would pass
#                                                   every absence assertion below for
#                                                   entirely the wrong reason.
#   3. No BOOT-INF/lib/h2-*.jar entry            -- the exclusion held.
#   4. No org/h2/** entry anywhere               -- catches H2 arriving flattened or via
#                                                   a transitive path rather than as the
#                                                   nested jar the exclusion names.
#
# What it does NOT check, and must never be described as checking: that no H2 console can
# be reached. `./mvnw spring-boot:run` on the default profile still has H2 on its runtime
# classpath by design, so a reflectively-registered console remains possible on a
# developer laptop. This gates the artifact, which is the part that reaches a server.
# See docs/adr/ADR-047-h2-excluded-from-deployable-artifact.md.
#
# Usage: ./mvnw package   (or verify), then tools/artifact/assert-no-h2-in-jar.sh
#
# Needs bash 4+ for `mapfile`. Fine on ubuntu-latest and on any Homebrew bash; stock macOS
# /bin/bash is 3.2 and would fail, which is why the shebang is `env bash` rather than a
# hardcoded /bin/bash.
set -euo pipefail

readonly TARGET_DIR="${1:-target}"

fail() {
    printf '\n  FAIL: %s\n\n' "$1" >&2
    exit 1
}

command -v unzip >/dev/null 2>&1 || fail "unzip is not on PATH; cannot inspect the artifact."

# The two `! -name` clauses exclude the -sources / -javadoc siblings, which would otherwise
# make "exactly one" untrue. The .original sibling that `repackage` leaves behind needs no
# clause: it ends in `.jar.original`, so `-name '*.jar'` never matches it in the first place.
mapfile -t jars < <(find "$TARGET_DIR" -maxdepth 1 -type f -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort)

if [ "${#jars[@]}" -eq 0 ]; then
    fail "no jar in ${TARGET_DIR}/. Run './mvnw package' first -- this script gates a build output, so with no output it must not pass."
fi
if [ "${#jars[@]}" -gt 1 ]; then
    fail "expected exactly one jar in ${TARGET_DIR}/, found ${#jars[@]}: ${jars[*]}. Refusing to guess which one is deployed."
fi

readonly JAR="${jars[0]}"
printf 'Inspecting %s\n' "$JAR"

readonly LISTING="$(unzip -Z1 "$JAR")"

# (2) Positive control first. If this fails, every absence check below is worthless.
if ! grep -qE '^BOOT-INF/lib/postgresql-[^/]*\.jar$' <<<"$LISTING"; then
    fail "no BOOT-INF/lib/postgresql-*.jar in ${JAR}. The positive control failed, so the H2 absence checks below would prove nothing. Either the jar is not a Boot repackage or the PostgreSQL driver has been dropped -- the deployed profile needs it."
fi
printf '  ok: PostgreSQL driver present (positive control)\n'

# (3) The exclusion itself.
if h2_jars="$(grep -E '^BOOT-INF/lib/h2-[^/]*\.jar$' <<<"$LISTING")"; then
    fail "H2 is back inside the artifact: ${h2_jars//$'\n'/ }
    The <excludes> block on the spring-boot-maven-plugin 'repackage' execution in pom.xml
    has stopped taking effect -- most likely it was removed, or hoisted to plugin level
    where a later goal override shadowed it. Re-read the comment there and ADR-047
    before changing this. Do not delete this check to make the build green: the nested
    jar carries 13 classes in org.h2.server.web, including JakartaWebServlet."
fi
printf '  ok: no BOOT-INF/lib/h2-*.jar\n'

# (4) The same claim, one layer down, in case H2 arrives some way the exclusion misses.
if h2_classes="$(grep -E '(^|/)org/h2/' <<<"$LISTING" | head -5)"; then
    fail "H2 classes are present in ${JAR} without a nested h2-*.jar, e.g.:
    ${h2_classes//$'\n'/
    }
    Check (3) passed, so the exclusion is working on the dependency it names and H2 is
    reaching the artifact another way -- a flattened/shaded build, or a transitive
    dependency that repackages H2 under its own coordinates."
fi
printf '  ok: no org/h2/** entries\n'

printf '\nPASS: %s carries no H2.\n' "$JAR"
