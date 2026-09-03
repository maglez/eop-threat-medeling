# ADR-063: The front end is a second SonarQube project with its own ratchet

**Status:** Accepted

**Date:** 2026-09-03

**Deciders:** Tech Lead, Architecture Guardian

## Context

[ADR-060](ADR-060-sonarqube-issue-ratchet.md) put three integers behind a gate. Reliability,
Maintainability and Security issue counts are harvested from a local SonarQube by
`tools/sonar/scan.sh`, committed to `tools/sonar/sonar-report.json`, and compared against the
ceiling in `tools/sonar/sonar-baseline.json` by the `sonar-ratchet` CI job. No server is involved
in the comparison and no LLM is in the path.

That gate has only ever measured Java. The scanner is invoked through the Maven plugin, so its
scope is the Maven project, and `tools/sonar/source-hash.sh` computed its freshness token over
`pom.xml` and the `.java` files under `src/main/java` and `src/test/java`. The front end under
`ui/` was named in that file's own list of what the hash does not cover.

The front end is not a placeholder. It is a React and TypeScript application with its own test
suite, its own lint configuration, its own Dockerfile and its own CI job, and it has been built,
tested, containerised and served since [ADR-009](ADR-009-frontend-react-typescript.md). Roughly two thousand
eight hundred lines of it were being shipped with no static analysis beyond ESLint and the
TypeScript compiler, while the Java half carried a gate that fails on a single new finding.

Two things prompted closing that gap now.

The first was a misdiagnosis worth recording, because it will recur. An operator ran a SonarQube
scan of this repository by hand and reported 60.5% coverage, against the 95.2% in the committed
report, and asked which coverage exclusions needed adding. No exclusions were needed and adding
them would have been the wrong fix. JaCoCo's `report` goal is bound to the `verify` phase, not to
`test`, so a scan following `mvn test` finds no coverage XML at all and SonarQube reports whatever
partial or absent data it was given. `tools/sonar/scan.sh` runs the full `verify` for exactly this
reason and says so in a comment. The operator's line count confirmed the front end was not in their
scope either, so `ui/` was never the cause of that particular number. The episode still made the
absence conspicuous: the question "why is front-end coverage not counted" had no good answer.

The second was that the front end had no coverage measurement of any kind. `ui/vite.config.ts`
configured Vitest with no coverage provider, `ui/package.json` declared no coverage dependency and
no coverage script, and no LCOV file was ever produced. Feeding `ui/` to a scanner in that state
would have counted every executable line as uncovered — a number that looks like a measurement and
is not one.

## Decision

The front end becomes a **second SonarQube project**, `eop-threat-modeling-ui`, with its own scan
script, its own committed report, its own committed baseline and its own CI job. The Java project
keeps its key, its report, its baseline and its ceiling untouched.

### Why two projects and not one polyglot project

SonarQube analyses several languages in one project perfectly well, and one project would have
been less machinery. It was rejected because the gate is three integers and a single set of three
would conflate two unrelated populations. A new front-end finding could then be paid for out of
Java headroom, or the reverse, and the number would still move truthfully while meaning nothing
about either population. The ratchet's whole value is that a rise is unambiguous.

Two further consequences follow from the split, both wanted. The two projects have independent
freshness tokens, so a Java-only change leaves the front-end report fresh and a front-end-only
change leaves the Java report fresh — neither language's authors are asked to boot a container and
rescan for the other's edit. And the two CI jobs fail independently, so a front-end regression
names itself rather than surfacing as a rise in a combined figure.

A third reason is practical: the two halves cannot be scanned by the same command. Java needs
`./mvnw verify` for JaCoCo and resolves the scanner as a Maven plugin. The front end needs
`npm run coverage` for Vitest's LCOV and has no `pom.xml` for a Maven plugin to attach to.

### Front-end coverage is measured

`@vitest/coverage-v8` is added to `ui/package.json`, a `coverage` script runs
`vitest run --coverage`, and `ui/vite.config.ts` gains a coverage block emitting `lcov` for
SonarQube and `text` for the human into the gitignored `ui/coverage/`. The `verify` script runs
`coverage` in place of `test`, because `vitest run --coverage` runs the same suite and writes the
report in one pass — keeping both would execute the suite twice for one artefact. The CI `ui` job
does the same.

`all: true` is set deliberately. Without it, coverage is computed only over files some test
imported, so adding an unreferenced module would *raise* the reported percentage.

**Coverage is recorded and not gated.** The report carries the figure and nothing compares it. No
`thresholds` block is configured in `ui/vite.config.ts`, and this is a decision rather than an
omission: JaCoCo already owns Java coverage limits, and putting a second numeric limit beside the
issue ratchet would be two numbers for one invariant, which is how two numbers drift apart. The
issue counts hold quality; the coverage figure is context for a human reading the report.

`src/main.tsx` is left in scope and reports zero coverage. It is the React mount point and the test
suite does not exercise it. Excluding it would have been the more flattering choice and the less
honest one, so the exclusion list covers only test files, the Testing Library setup shim and the
ambient declaration file — the last of which emits no statements at all and therefore cannot move a
percentage in either direction.

### The scanner runs in a digest-pinned container

The front end is scanned by `sonarsource/sonar-scanner-cli` pinned by digest to
`sha256:23ca0f137965d9dff2198074043fd48d386280bc5d0ccac8c8349cea4cf096a9`, which is SonarScanner
CLI 8.0.1.6346. The rejected alternative was a `brew install sonar-scanner` prerequisite in the
style of the k6 install documented for performance testing. Four reasons for the container:

1. **The pin is exact.** The Java path resolves its scanner by fully-qualified Maven coordinates
   including a version, so that half is already reproducible. A brew-installed binary floats with
   the next `brew upgrade`.
2. **The report records the scanner.** A floating scanner would silently change a committed
   artefact — the same class of defect the freshness hash exists to prevent.
3. **It adds no prerequisite.** The scan cannot run at all without a live SonarQube, and SonarQube
   runs in a container via `compose.sonar.yml`, so a container runtime is already required.
4. **Digest pinning is the local convention.** `compose.sonar.yml` pins the server by digest rather
   than by tag for the same reason.

Two operational facts about that image are worth knowing before it surprises somebody. It is
**amd64 only**, so on Apple silicon it runs under emulation; `--platform linux/amd64` is passed
explicitly so the platform is a decision in the script rather than a warning in its output. And it
is run as the invoking user, which makes both of the image's default write targets unwritable, so
`SONAR_USER_HOME` and `SCANNER_WORKDIR_PATH` are overridden. The second override earns its place
twice: without it the scanner cannot create its temporary directory at all, and at its default the
working directory would sit inside the container, so the task file the compute-engine poll reads
would vanish with it and the script could not tell whether the harvested numbers came from this
analysis or the previous one.

The container is attached to the host network. The alternatives — the compose network, or the
bridge with a special host name — were both probed and both reach the server. Host networking wins
because only the scanner runs in a container: the status preflight, the token minting and the
harvesting all run on the developer's machine, and host networking is the only option where **one
`SONAR_URL` string is correct on both sides of the container boundary**. It needs no rewriting of
`localhost`, no coupling to a compose network name, and it still works when `SONAR_URL` names a
remote server.

### Every scanner path is relative to `ui/`

The scanner runs with its base directory set to `ui`, sources at `src`, and the LCOV report at
`coverage/lcov.info`. This is forced by Vitest, whose LCOV file names its source files relative to
`ui/`. Pointing sources at `ui/src` from the repository root would analyse exactly the same files
and then fail to match a single coverage record, reporting zero per cent while looking entirely
healthy.

### One implementation, two flavours

`tools/sonar/write-report.py`, `tools/sonar/seed-baseline.py` and `tools/sonar/ratchet.py` are
parameterised rather than copied. Each carries a small table of the differences between the two
projects — the prose in the generated file, the default report and baseline paths, the shell
function that computes the freshness token, the command names printed in remedy messages, and
whether provenance records a Maven coordinate or a container digest. Nothing in the comparison
logic branches on the flavour, because the two projects differ in what they measure and not in how
a ceiling works.

This follows the warning `tools/sonar/source-hash.sh` already carries about two copies of one
invariant. The delicate logic in these scripts is the refusal to compare a stale report and the
refusal to infer a missing count as zero, and two copies of a refusal is one copy that can rot
unnoticed while the other is maintained.

The flavour defaults to Java everywhere, so `tools/sonar/scan.sh`, `tools/sonar/ratchet.sh`, the
command recorded in ADR-060 and every existing remedy message keep working unchanged. The front end
is the addition, so the front end is the one that names itself. The Java branch was checked to
produce byte-identical output to its previous implementation, which is what allows this ADR to
claim the Java path was not touched rather than merely re-tested.

Two thin wrappers are new: `tools/sonar/scan-ui.sh` and `tools/sonar/ratchet-ui.sh`. The second is
four lines of substance for the same reason the Java one is.

### The freshness tokens are disjoint

`sonar_ui_source_hash` covers `ui/package.json`, `ui/tsconfig.json`, `ui/vite.config.ts` and every
`.ts` and `.tsx` file under `ui/src`. Each of the three configuration files earns its place: the
TypeScript configuration decides which rules can fire, the Vite configuration carries the coverage
exclusions, and the package manifest carries the versions.

`ui/package-lock.json` is deliberately excluded. The TypeScript sensor reads the configuration and
the source, not the lockfile, so a lockfile change cannot move the counts — and including a large
generated file that changes on every dependency audit would train people to rescan mechanically,
which is the opposite of what a freshness check is for.

### The CI job

`sonar-ratchet-ui` is modelled on `sonar-ratchet`: it checks out the repository and runs one
script. It starts no server, installs no toolchain and reaches no network. Notably it runs no Node
at all despite its subject being TypeScript — a `setup-node` step appearing in it later would be
the same warning sign a `setup-java` step would be in the Java job.

Neither job declares a dependency on the other. They read different files and share nothing, so
chaining them would only serialise two file comparisons and hide the second failure behind the
first. Like the Java job, it is not a required status check yet, for ADR-060's reason: the friction
of the stale-report failure should be measured before it can block a merge.

## Consequences

### What this buys

Front-end static analysis is now gated at the same standard as Java. A new Reliability or
Maintainability finding in `ui/src` fails a CI job, where previously it would have shipped
unremarked. The seeded ceiling is nine Reliability findings, twenty-six Maintainability findings
and zero Security findings, recorded with twenty-six diagnostic fingerprints.

Front-end coverage exists as a number for the first time, measured at just under ninety-one per
cent over roughly two thousand eight hundred lines. It is not gated, but it is now visible and
regressions in it are visible too.

A latent defect in the shared report writer was found and fixed on the way. Its first consistency
check compared a per-quality total against a per-finding list length. Those two numbers coincide
only when no finding carries more than one software quality, which is true of the Java project
today by luck rather than by construction. The front end has nine findings carrying two qualities
each, so the very first front-end scan emitted a warning whose text named a failure — issues missed
by pagination — that had not occurred. It is now two independent checks, each of which can only
fire for its own reason. The gate itself was always correct on this point; the diagnostic was not.

### What this costs

There are now two scans to run and two reports to keep fresh. A change to both halves of the
repository means booting SonarQube once and running two scripts. The scripts are independent, so
neither can be forgotten silently — the corresponding ratchet fails on a stale report — but this is
genuinely more work than one command.

The scanner container is emulated on Apple silicon and the front-end scan is correspondingly slow,
taking well over a minute where the analysis itself is a fraction of that. This is tolerable for a
local, occasional operation and would not be tolerable in a pipeline.

The front-end scan depends on a container image and a container runtime in a way the Java scan does
not. Moving the digest is a supply-chain change and a baseline-invalidating one, exactly as moving
the server digest is: a scanner upgrade ships retuned rules, and retuned rules move the counts for
reasons that have nothing to do with the code.

Two SonarQube projects mean two dashboards, and nothing joins them. There is no single number for
the repository's quality and there deliberately is not one.

### What this does not do

It does not prove the front end is good code. It proves three integers did not increase.

It does not gate coverage. A change that deletes tests and lowers front-end coverage from
ninety-one per cent to forty passes `sonar-ratchet-ui` without complaint, provided it introduces no
new issues.

It does not analyse anything outside `ui/src` and the Maven project. The repository's shell
scripts, its workflow files, its Liquibase changelogs and its resources are still unanalysed by
SonarQube, and the freshness hashes say so.

It says nothing about the front-end test files. Their findings are measured and recorded in the
report under the test scope, and not gated, for the reason ADR-060 gives for the Java project: a
ceiling with no headroom over test code turns a routine new test file into a red gate that has
nothing to say about the product.

It does not close the possibility of hand-editing a committed report. The freshness hash closes the
accident of forgetting to rescan, not the intent to lie. That remains a matter for review, as it
does on the Java side and as it does for the accepted-advisory allowlist.

## Related

- [ADR-060](ADR-060-sonarqube-issue-ratchet.md) — the Java issue ratchet this extends, and the
  source of the no-live-server-in-CI constraint, the strict per-quality comparison and the
  test-scope narrowing
- [ADR-061](ADR-061-two-new-dod-gates-sonar-ratchet-and-cve.md) — makes the ratchet a
  Definition-of-Done gate adjudicated by a review agent, which now covers this project too
- [ADR-009](ADR-009-frontend-react-typescript.md) — the React and TypeScript front end being analysed
- [ADR-037](ADR-037-frontend-build-time-feature-flags.md) — front-end build-time flags, a separate
  mechanism from the back-end feature flags, and the reason no feature flag applies to this change
- [ADR-016](ADR-016-local-container-runtime.md) — Colima as the container runtime, which is why
  host networking behaves as described and why Docker Desktop's behaviour is out of scope
- [ADR-006](ADR-006-build-quality-gates.md) — the build quality gates that run inside
  `./mvnw verify`, which this gate deliberately sits outside
