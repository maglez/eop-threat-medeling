#!/usr/bin/env bash
# Shared definition of the "what was scanned" fingerprint. Sourced by both
# tools/sonar/scan.sh (which records it) and tools/sonar/ratchet.sh (which
# re-computes it and refuses a stale report). See ADR-060.
#
# This file exists purely so that there is exactly one definition of the hash.
# An earlier sketch of this gate inlined the same `find | shasum` pipeline in
# both scripts, which is the classic two-copies-of-one-invariant problem: the
# moment the two disagree the gate either fails every run (mismatch that is
# not really staleness) or passes every run (both sides computing something
# nobody intended). Neither failure announces itself as a drift bug. Keep the
# definition here and source it.
#
# Scope, stated explicitly because the hash is only as honest as its inputs:
# we hash `pom.xml` plus every `.java` file under `src/main/java` and
# `src/test/java`. That is exactly what SonarQube analysed - the scan log
# shows only the XML sensor and the JaCoCo importer running beyond Java, and
# the languages facet returns `java: 243` with nothing else, so no other file
# type contributes an issue today.
#
# `pom.xml` is in the set even though it is not Java, and that is deliberate
# rather than belt-and-braces: it carries the dependency versions and the
# plugin configuration that decide which rules run and what the analysis sees,
# so a pom change can move the issue counts without a single source line
# changing. Leaving it out would create a category of edit that silently
# invalidates the report while the gate reports everything fresh.
#
# What this hash does NOT cover, so that nobody reads more into a green gate
# than it earns: resources under `src/main/resources` (the XML sensor runs
# over them but produces no issue today), the workflow files, and the shell
# scripts in this directory. If any of those ever start producing SonarQube
# issues, they must be added here in the same commit that widens
# `sonar.sources` - and adding them changes the hash for everybody, which is a
# one-off forced rescan and the reason to do it deliberately rather than as a
# drive-by.
#
# The front end under `ui/` is NOT in `sonar_source_hash` either, but for a
# different reason and with a different remedy: since ADR-063 it is a separate
# SonarQube project with its own report, its own baseline and its own ratchet,
# so it has its own fingerprint in `sonar_ui_source_hash` below. The two are
# deliberately disjoint - that is what lets a Java-only change leave the front
# end's report fresh and a front-end-only change leave the Java report fresh,
# rather than forcing a rescan of a tree nobody touched.

set -euo pipefail

# Portable SHA-256 of stdin. `sha256sum` is coreutils and present on the CI
# runner; `shasum -a 256` is the perl one and present on macOS. We need the
# same digest from both, and both emit "<hex>  -", so we cut the hex out.
_sonar_sha256_stdin() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | cut -d' ' -f1
    else
        shasum -a 256 | cut -d' ' -f1
    fi
}

_sonar_sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1"
    else
        shasum -a 256 "$1"
    fi
}

# Print the source hash for the current working tree. Must be run from the
# repository root.
sonar_source_hash() {
    local file_list
    # `LC_ALL=C sort` rather than a bare `sort`: the byte-order collation is
    # the only one that is identical on macOS and on the runner. A locale-
    # sensitive sort would order paths containing `-` or `_` differently on
    # the two platforms and produce a spurious staleness failure that would
    # look, from CI, exactly like a developer who forgot to rescan.
    file_list=$(
        {
            printf '%s\n' pom.xml
            find src/main/java src/test/java -type f -name '*.java'
        } | LC_ALL=C sort
    )

    # We hash the per-file digests rather than concatenating file contents,
    # because the per-file output includes the path. That makes a pure rename
    # change the hash - which it must, since SonarQube keys issues by
    # component path and a rename moves every issue in the file.
    while IFS= read -r f; do
        _sonar_sha256_file "$f"
    done <<<"$file_list" | _sonar_sha256_stdin
}

# Count of files in the hash set, recorded alongside the hash in the report so
# that a human reading a mismatch can tell "I edited one file" from "the tree
# I am comparing against is a different shape entirely".
sonar_source_file_count() {
    {
        printf '%s\n' pom.xml
        find src/main/java src/test/java -type f -name '*.java'
    } | wc -l | tr -d ' '
}

# ---------------------------------------------------------------------------
# Front end (ADR-063)
# ---------------------------------------------------------------------------
# The same fingerprint for the `eop-threat-modeling-ui` project. Separate
# functions rather than a parameter on the pair above, because the two hashes
# must be independently stale: the whole point of two SonarQube projects is
# that a Java change does not invalidate the front end's report and vice
# versa, and a single parameterised hash over both trees would reinstate
# exactly the coupling the split removes.
#
# The file set is every `.ts`/`.tsx` under `ui/src` - which is what the scanner
# analyses - plus three configuration files that decide *how* it is analysed:
#
#   ui/tsconfig.json   the compiler options SonarQube's TypeScript sensor
#                      reads to resolve types. `strict` going false would
#                      change which rules can fire without touching a source
#                      line, so it belongs in the fingerprint for the same
#                      reason `pom.xml` does on the Java side.
#   ui/vite.config.ts  carries the `coverage` block. Changing an `exclude`
#                      pattern moves the coverage measurement while every
#                      source file stays byte-identical.
#   ui/package.json    the dependency versions, and therefore which analyser
#                      and which ESLint-adjacent rules are in play.
#
# `ui/package-lock.json` is deliberately NOT in the set. It is a large
# generated file that changes on every `npm audit fix` and every transitive
# bump, none of which alters what the TypeScript sensor sees - the sensor
# reads `tsconfig.json` and source, not the lockfile. Including it would force
# a full rescan for a class of change that provably cannot move the counts,
# which trains people to rescan mechanically instead of reading why the gate
# went red. The direct dependency versions that *can* matter are in
# `package.json`, which is in the set.
_sonar_ui_file_list() {
    {
        printf '%s\n' ui/package.json ui/tsconfig.json ui/vite.config.ts
        find ui/src -type f \( -name '*.ts' -o -name '*.tsx' \)
    } | LC_ALL=C sort
}

# Print the front-end source hash for the current working tree. Must be run
# from the repository root.
sonar_ui_source_hash() {
    local file_list
    file_list=$(_sonar_ui_file_list)

    while IFS= read -r f; do
        _sonar_sha256_file "$f"
    done <<<"$file_list" | _sonar_sha256_stdin
}

# Count of files in the front-end hash set. Same purpose as the Java count
# above: it turns "the hash moved" into "the hash moved and the tree is a
# different shape", which is the difference between a forgotten rescan and a
# comparison against the wrong commit.
sonar_ui_source_file_count() {
    _sonar_ui_file_list | wc -l | tr -d ' '
}
