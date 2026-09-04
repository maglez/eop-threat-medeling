# ADR-064: Digest-pinned containers are audited as a roster, not one image at a time

**Status:** Accepted

**Date:** 2026-09-04

**Deciders:** @tech-lead, @architecture-guardian, @security-auditor

## Context

[ADR-055](ADR-055-k6-performance-check-in-ci.md) introduced the first digest-pinned
external container into CI — `grafana/k6:2.2.0@sha256:9bd01d69…`. During that story's
Definition-of-Done round, @security-auditor observed that `tools/supply-chain/` audits
the seven OpenCode npm plugins baselined in `expected-plugins.json` and **nothing else**:
the container layer, the GitHub Actions layer and the whole Maven plugin layer are
uncovered. The finding was raised explicitly as non-blocking, and it was right to be —
the k6 image is digest-pinned and carries buildx provenance and SBOM attestations, which
is a *stronger* posture than the unpinned Actions already in the same workflow. EOP-159
is that observation as a story.

Two facts shape the decision.

**A digest pin is not an npm pin.** `audit-plugins.sh` earns its keep because an npm
version specifier names a mutable target: the registry can serve different bytes for the
same version, maintainership can change hands, and an advisory can be filed against a
version already installed. None of that applies to `repo@sha256:…`, which *is* the
content. So a container audit modelled on the plugin audit would be a tripwire for an
event that cannot happen, and describing it as compromise detection would be false
advertising.

**The real hazards here are procedural, and two of them have already occurred.** ADR-055
amendment 2 records two traps hit empirically while deriving that one pin: the tag is
`2.2.0` and not `v2.2.0`, and the pin must be the OCI **index** digest read from the
top-level `Digest:` of `docker buildx imagetools inspect` — never a per-platform
sub-manifest (pinning arm64 breaks `ubuntu-latest` with "no matching manifest for
linux/amd64") and never the output of `docker inspect` on a developer Mac, which reports
a third digest again. Both traps are recorded in prose. Prose does not run.

A third hazard was found while surveying the repository for this story, and it is live.
`sonarsource/sonar-scanner-cli` is pinned in **three** committed files —
`tools/sonar/scan-ui.sh`, `tools/sonar/sonar-ui-baseline.json` and
`tools/sonar/sonar-ui-report.json`, the latter two under the key `scannerImage`. Bumping
the digest in the script without rescanning leaves two committed files asserting a
scanner that was not used, and **nothing catches it**: `sonar-ratchet-ui`'s `sourceHash`
covers `ui/package.json`, `ui/tsconfig.json`, `ui/vite.config.ts` and `ui/src/**`, so an
edit to `scan-ui.sh` does not invalidate the report it produced.

## Decision

Add `tools/supply-chain/audit-containers.sh` and `tools/supply-chain/expected-containers.json`,
and run the script as a step in the existing non-required `supply-chain` CI job.

**Scope is every digest-pinned container in the repository, not the k6 image alone.**
There are three: `grafana/k6` (`.github/workflows/ci.yml`), `sonarqube`
(`compose.sonar.yml`) and `sonarsource/sonar-scanner-cli` (the three files above).
Covering one of three under a file named `expected-containers.json` would be a misleading
tripwire, by exactly the argument `audit-plugins.sh` already makes about roster drift: a
pin with no baseline entry is a pin nothing checks.

**What the audit checks**, in order of how much a failure would actually tell you:

1. **Roster drift, bidirectionally and hermetically.** Every `name@sha256:…` reference in
   a tracked file is discovered by regex; each distinct image must have exactly one
   baseline entry and each baseline entry must be pinned somewhere. The reverse direction
   also catches *unpinning* — replacing a digest reference with a bare tag orphans the
   baseline entry — and the failure text says so, because that is not the reading a
   reader reaches for first.
2. **Mirror agreement, hermetically.** Where an image is pinned in more than one file, all
   occurrences must carry the same digest, and the set of occurrence paths must equal the
   baseline's. This is the check that closes the `scannerImage` hole above.
3. **Pin form, hermetically.** `sha256:` plus 64 lowercase hex; a `latest` tag is rejected
   outright; the baseline's field set is enforced in both directions so a surplus or
   misspelled field fails rather than reading as a harmless extra.
4. **Registry shape, over the network.** `docker buildx imagetools inspect --raw` — chosen
   over hand-rolled registry auth *and* over `docker manifest inspect` so that the audit
   and ADR-055's pinning procedure name the same authoritative tool. The digest must still
   resolve; `mediaType`, the platform list and the attestation count must match the
   baseline. **`linux/amd64` must be reachable, but only for an image whose occurrences
   include a path under `.github/workflows/`** — that is ADR-055's trap 2 encoded for
   every future pin, and gating it on CI usage lets a local-only image be legitimately
   single-platform.
5. **Tag drift, reported and never gated.** Where a reference carries a tag, the tag's
   current digest is compared to the pin and any difference is printed. A mutable tag
   moving is ordinary upstream behaviour and is the entire reason the digest is there;
   failing on it would invert the mechanism. This is the inverse of the npm case, where
   the pin is an exact version and movement *is* the signal.

**`mediaType` is compared against the baseline rather than required to be an index.** The
survey found that `sonarsource/sonar-scanner-cli` is legitimately *not* an index — it is a
single-platform `application/vnd.docker.distribution.manifest.v2+json` with no `manifests`
array at all, and therefore no attestations, its platform readable only from the config
blob. A universal "must be an index" rule would have been wrong on a third of the roster.
Comparing to the baseline makes *drift* the failure, which is the correct tripwire posture
and matches how `expected-plugins.json` already treats `provenance: false`.

**Baseline fields**, seven per entry, all mandatory: `tag` (which **may be `null`** — two
of the three references are tagless, and mandatory-but-nullable is deliberately the
`expiry: null` idiom from the feature-flag registry), `digest`, `mediaType`, `platforms`,
`attestations`, `occurrences`, `note`.

**A registry failure exits 2, "could not run", not 0.** An unreachable registry, an
anonymous Docker Hub rate limit and an offline laptop are indistinguishable from inside
the script, and none of them is evidence a pin is wrong. Finding *zero* pinned references
also exits 2 rather than passing, because a clean scan of nothing is the failure mode a
discovery regex has.

**Wiring:** a new step in the existing `supply-chain` job, placed immediately after
checkout and *before* `Set up Node 22`, so container feedback does not wait on an npm
install it does not need; and `if: always()` added to the existing plugin-audit step so a
container failure cannot hide plugin drift. No new job — that would duplicate a checkout
for a five-second script, and the job name `supply-chain` already generalises.

### Scope deliberately not taken

- **The GitHub Actions layer.** Nine distinct actions over fifteen call sites, none
  digest-pinned. This is pre-existing posture and explicitly *not* an EOP-155 regression,
  and pinning them by digest without Dependabot to move the pins is a maintenance
  treadmill that would be abandoned. A separate story, honestly scoped, or not at all.
- **The Maven plugin layer.** Fifteen bound plugins and about thirty-one in the effective
  `pluginManagement`, most of their versions owned by the Spring Boot parent, so a parent
  bump moves about thirty in one line. That is the largest genuinely uncovered surface in
  the repository, and it is a different mechanism — a version range resolved at build
  time, not a content address. It needs its own decision.
- **Image CVE scanning.** Rejected reusing the argument `.github/workflows/ci.yml` already
  makes for keeping `dependency-cve` off `.opencode/` and `tools/`: two gating scanners
  over one tree means two allowlists to keep in step, and the first divergence would be
  silent. These three containers are CI and developer tooling and are not shipped, so
  ADR-050's scope is unchanged.

## Consequences

- The two pinning traps ADR-055 discovered are now enforced for every *future* pin rather
  than recorded for one past pin. The amd64 check was negative-tested against the real
  historical mistake — k6's genuine arm64 child digest, with a fully self-consistent
  baseline describing it accurately — and caught it on that check alone.
- The live `scannerImage` mirror hole is closed. Sixteen negative tests were run against
  the script and all sixteen fail with an accurate first finding; the mirror case was
  produced by editing the digest in `sonar-ui-report.json` only.
- **This audit cannot tell you an image went bad, and must never be described as though it
  could.** It reads no image contents, no CVE feed and no signatures. It answers three
  questions: is every pinned container declared, is each pin well-formed and usable where
  it is used, and do the mirrored copies of a digest still agree. A green run is not a
  statement about the software inside those images.
- The job now depends on the Docker Hub registry being reachable and on anonymous pull
  rate limits on shared runner IPs. `supply-chain` is not a required check, so a flake
  costs a re-run rather than a blocked merge — but it *is* a new source of red that is
  nobody's fault, and treating exit 2 as equivalent to exit 1 would train people to ignore
  the job.
- Bumping a container pin is now a two-file change: the pin and its baseline entry, in the
  same reviewed commit. Never the baseline alone to turn a red job green.
- Three pinned containers is a small enough roster that the baseline could be read by eye.
  The value is in the checks that are *not* eye-readable — registry shape and mirror
  agreement — and in the roster growing without anyone remembering to look.
- Two layers remain uncovered and are now named as uncovered rather than merely unnoticed,
  which is a smaller improvement than closing them but a real one.

## Related

- [ADR-055](ADR-055-k6-performance-check-in-ci.md) — introduced the k6 pin and recorded the
  two traps this script enforces
- [ADR-050](ADR-050-dependency-cve-scanning.md) — the Trivy CVE gate over shipped
  dependencies, whose scope this decision deliberately leaves alone
- [ADR-063](ADR-063-sonarqube-frontend-project.md) — introduced the `scannerImage` mirrors
  whose divergence check 2 closes
- [ADR-016](ADR-016-local-container-runtime.md) — Colima as the local container runtime
- `tools/supply-chain/audit-plugins.sh` — the npm-plugin audit this one is modelled on and
  deliberately differs from
