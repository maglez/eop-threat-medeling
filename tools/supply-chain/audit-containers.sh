#!/usr/bin/env bash
#
# Audits the digest-pinned container images this repository runs.
#
# Why this exists. EOP-155 put the first pinned external container into CI, and the review that
# followed noted that tools/supply-chain/ covered the seven OpenCode npm plugins and nothing else --
# so the container layer was pinned carefully by hand and then watched by nobody. Prose in an ADR
# recorded how to derive a container pin correctly. Prose does not run. This script does.
#
# What it checks, in order of how much it would actually tell you:
#
#   0. ITS OWN PARSER, before it opens a single file. Every other check here compares two things and
#      reports the difference, so it fails loudly. The reference parse is the one step that can fail
#      SILENTLY: a pin the regex never matches is a pin this script never mentions, and the run goes
#      green. So REF_CASES pins nine readings -- including a registry host with a port, which an
#      earlier revision misparsed, and a bare "sha256:..." in a comment, which must NOT be read as a
#      reference. A mismatch fails the run before any discovery output is printed.
#
#   1. ROSTER DRIFT, bidirectionally, and without touching the network. Every "name@sha256:..."
#      reference in a tracked file must have a baseline entry, and every baseline entry must still
#      be referenced. The second direction is not symmetry for its own sake: replacing a digest with
#      a floating tag ORPHANS the baseline entry, so unpinning fails here rather than passing
#      quietly, which is the failure mode a pin exists to prevent.
#
#   2. MIRROR AGREEMENT, also hermetic. A digest that appears in more than one file must be the
#      same digest in all of them. This closes a hole that is live today rather than theoretical:
#      sonarsource/sonar-scanner-cli appears in tools/sonar/scan-ui.sh and in the two committed
#      JSONs that record which scanner produced them, and sonar-ratchet-ui's sourceHash covers only
#      ui/package.json, ui/tsconfig.json, ui/vite.config.ts and ui/src/** -- so bumping the scanner
#      without rescanning leaves the report naming a scanner that never ran, and nothing else in
#      the build notices.
#
#   3. PIN FORM. sha256 plus 64 hex, and the tag beside it -- if there is one -- is not "latest".
#      A digest beside :latest is not wrong, but it is a pin whose author was thinking about a tag.
#
#   4. REGISTRY SHAPE, over the network. The digest must still resolve, and its mediaType, platform
#      list and attestation count must match the baseline. For an image CI runs, linux/amd64 must
#      be reachable at that digest -- this is the ADR-055 trap that cost a red build once: pinning
#      a per-platform sub-manifest from an arm64 developer Mac breaks ubuntu-latest with "no
#      matching manifest for linux/amd64". Encoding it here means the next pin cannot repeat it.
#
#   5. TAG DRIFT, over the network, reported and NEVER gated. Where a reference carries a tag, the
#      tag's current digest is compared to the pin. A mutable tag moving is ordinary upstream
#      behaviour and is precisely what a digest defends against, so this is news, not a failure.
#      Note this is the inverse of the npm case, where a pin names an exact version and a moved
#      version IS the incident.
#
# What it does NOT check, and must never be described as checking. It does not tell you an image is
# safe, and it cannot. A digest is content-addressed, so there is no container equivalent of the npm
# maintainer handoff that audit-plugins.sh watches for -- nobody can alter what a pinned digest
# resolves to. This is a COVERAGE AND PROCEDURE gate: it proves every pinned container is declared,
# shaped correctly for where it runs, and consistently recorded. It scans no image contents, reads
# no CVE feed and verifies no signature. Image CVE scanning was considered and rejected in ADR-064:
# it would mean a third gating scanner and a third allowlist over this tree, and ci.yml's own
# argument for keeping dependency-cve narrow applies unchanged -- two allowlists to keep in step,
# and the first divergence is silent.
#
# It also covers containers only. The GitHub Actions in .github/workflows/ are still floating major
# tags and the whole Maven plugin layer is still unwatched; both were considered and deferred in
# ADR-064, which records why.
#
# Usage: tools/supply-chain/audit-containers.sh
# Exit:  0 = clean, 1 = drift or a malformed pin, 2 = could not run.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

baseline="tools/supply-chain/expected-containers.json"
workdir=".tmp/supply-chain-containers"

# Inside the worktree on purpose: .tmp/ is gitignored and needs no external_directory grant,
# unlike /tmp. See AGENTS.md. Guarded because a permissions failure here is could-not-run,
# not a clean tree: under `set -e` an unguarded failure exits with the shell's own code --
# typically 1, which this script's contract reserves for drift or a malformed pin -- so an
# unwritable workspace would read as a finding. scan-dependencies.sh and audit-plugins.sh
# carry the same guards for the same reason; the three were fixed together (EOP-146).
rm -rf "$workdir" || { echo "FATAL: could not remove $workdir"; exit 2; }
mkdir -p "$workdir" || { echo "FATAL: could not create $workdir"; exit 2; }

command -v git >/dev/null 2>&1 || { echo "FATAL: git is not on PATH"; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "FATAL: python3 is not on PATH"; exit 2; }
command -v docker >/dev/null 2>&1 || { echo "FATAL: docker is not on PATH"; exit 2; }
docker buildx version >/dev/null 2>&1 || {
    echo "FATAL: 'docker buildx' is unavailable. This audit reads manifests with"
    echo "       'docker buildx imagetools inspect', which ADR-055 names as the authoritative"
    echo "       source for a container digest. Do not substitute 'docker inspect' -- on a"
    echo "       developer machine it reports a different digest entirely."
    exit 2
}
[[ -f "$baseline" ]] || {
    echo "FATAL: baseline $baseline is missing. It is the only record of what these pins"
    echo "       resolved to when they were last reviewed; without it there is nothing to"
    echo "       compare against and the audit must not pass by default."
    exit 2
}

# ---------------------------------------------------------------------------------------------
echo "=== Discovering digest-pinned container references in tracked files ==="

git ls-files -z > "$workdir/tracked.z"

python3 - "$baseline" "$workdir" <<'PY'
import json
import re
import sys
from pathlib import Path

baseline_path, workdir = sys.argv[1], sys.argv[2]

# A reference is name[:tag]@sha256:<64 hex>. The negative lookbehind stops a longer path being
# clipped mid-token. Bare "sha256:..." digests are deliberately NOT matched: compose.sonar.yml and
# ADR-055 both quote child platform digests that way, and they are records of what an index
# contains rather than references anything runs.
#
# The leading "host:port/" alternative is load-bearing and was added after review (EOP-159). A
# registry host is otherwise indistinguishable from a path component -- "ghcr.io/owner/name" needs
# no special case, because a dot is already legal in a path component -- but a host carrying an
# explicit PORT does, because the colon is not. Without that alternative,
# "registry.example.com:5000/img@sha256:..." parsed as image "5000/img" with no tag: a wrong name
# rather than a silent miss, so the roster check would have failed loudly on an image nobody
# declared, but with a finding naming something that does not exist. The alternative demands
# ":<digits>/" so it can never swallow an ordinary first path component: "grafana/k6" has no colon
# and is unaffected. REF_CASES below pins every one of these readings.
REF = re.compile(
    rb"(?<![\w./@-])"
    rb"((?:[a-z0-9][a-z0-9.-]*:[0-9]+/)?"
    rb"[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*)"
    rb"(?::([A-Za-z0-9_][A-Za-z0-9._-]*))?"
    rb"@(sha256:[0-9a-f]{64})"
)

# The parse is the one part of this audit that can fail SILENTLY -- every other check compares two
# things and says so when they differ, but a reference the regex does not see is a pin the audit
# never mentions. So the readings are pinned here as a table and asserted on every run, before any
# file is opened. Cheap enough to be unconditional, and unconditional is the point: a table that
# only runs when someone remembers to ask is a table that rots.
_D = b"sha256:" + b"0" * 64
REF_CASES = (
    # blob                                              image                            tag
    (b"    image: sonarqube@" + _D,                      "sonarqube",                     None),
    (b"  grafana/k6:2.2.0@" + _D,                        "grafana/k6",                    "2.2.0"),
    (b'IMG="ghcr.io/maglez/eop@' + _D + b'"',            "ghcr.io/maglez/eop",            None),
    (b'IMG="ghcr.io/maglez/eop:1.2.3@' + _D + b'"',      "ghcr.io/maglez/eop",            "1.2.3"),
    (b"  registry.example.com:5000/img@" + _D,           "registry.example.com:5000/img",  None),
    (b"  registry.example.com:5000/ns/img:v1@" + _D,     "registry.example.com:5000/ns/img", "v1"),
    (b"  localhost:5000/img@" + _D,                      "localhost:5000/img",            None),
    (b"  a/b/c/d@" + _D,                                 "a/b/c/d",                       None),
    # A bare child-platform digest, as compose.sonar.yml and ADR-055 quote them: not a reference.
    (b"  #   linux/arm64 -> " + _D,                      None,                            None),
)
_self_test_failures = []
for _blob, _want_image, _want_tag in REF_CASES:
    _m = REF.search(_blob)
    _got_image = _m.group(1).decode() if _m else None
    _got_tag = _m.group(2).decode() if (_m and _m.group(2)) else None
    if (_got_image, _got_tag) != (_want_image, _want_tag):
        _self_test_failures.append(
            f"reference parse regressed on {_blob.decode()!r}: "
            f"expected image={_want_image!r} tag={_want_tag!r}, got image={_got_image!r} tag={_got_tag!r}"
        )
if _self_test_failures:
    print("=== REFERENCE PARSER SELF-TEST FAILED ===")
    for _f in _self_test_failures:
        print(f"  - {_f}")
    print()
    print("  The discovery regex no longer reads references the way this audit documents. Every")
    print("  other check is downstream of it, so a PASS below would mean nothing. Fix the regex")
    print("  or, if a reading genuinely changed, change REF_CASES in the same reviewed commit.")
    sys.exit(1)

# docs/jira-export/ is a frozen third-party dump of the decommissioned Jira instance; it is history,
# not configuration. The baseline itself is skipped so that a note may quote a full reference as
# prose without the audit reading its own documentation as a pin site.
SKIP_PREFIXES = ("docs/jira-export/",)
SKIP_EXACT = {baseline_path}

paths = [p for p in Path(workdir, "tracked.z").read_bytes().split(b"\x00") if p]

found = {}   # image -> {"tags": set, "digests": set, "occurrences": set}
for raw in paths:
    rel = raw.decode("utf-8", "replace")
    if rel in SKIP_EXACT or rel.startswith(SKIP_PREFIXES):
        continue
    try:
        blob = Path(rel).read_bytes()
    except (OSError, IsADirectoryError):
        continue
    if b"@sha256:" not in blob:
        continue
    for m in REF.finditer(blob):
        image = m.group(1).decode()
        tag = m.group(2).decode() if m.group(2) else None
        digest = m.group(3).decode()
        e = found.setdefault(image, {"tags": set(), "digests": set(), "occurrences": set()})
        e["tags"].add(tag)
        e["digests"].add(digest)
        e["occurrences"].add(rel)

out = {
    img: {
        "tags": sorted(t for t in e["tags"] if t is not None),
        "tagless": None in e["tags"],
        "digests": sorted(e["digests"]),
        "occurrences": sorted(e["occurrences"]),
    }
    for img, e in found.items()
}
Path(workdir, "found.json").write_text(json.dumps(out, indent=2, sort_keys=True))

if not out:
    print("  found no digest-pinned references at all -- that is not credible for this")
    print("  repository, so treating it as a broken scan rather than a clean result.")
    sys.exit(2)

for img in sorted(out):
    e = out[img]
    tag = e["tags"][0] if e["tags"] else "(tagless)"
    print(f"  {img}  tag={tag}  {len(e['occurrences'])} occurrence(s)")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Roster, occurrence and pin-form drift against the baseline ==="

python3 - "$baseline" "$workdir" <<'PY'
import json
import re
import sys
from pathlib import Path

baseline_path, workdir = sys.argv[1], sys.argv[2]
expected = json.loads(Path(baseline_path).read_text())["containers"]
found = json.loads(Path(workdir, "found.json").read_text())

DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
REQUIRED = {"tag", "digest", "mediaType", "platforms", "attestations", "occurrences", "note"}

failures = []

for img in sorted(set(found) - set(expected)):
    failures.append(
        f"{img} is pinned in the repository but absent from {baseline_path}. A container with "
        f"no baseline entry is a container nothing checks -- add it in the same commit that "
        f"adds the pin, deriving its values with "
        f"'docker buildx imagetools inspect {img}@<digest>'."
    )

for img in sorted(set(expected) - set(found)):
    failures.append(
        f"{img} is in the baseline but no longer pinned by digest anywhere. Either it was "
        f"removed deliberately -- in which case remove its baseline entry too -- or a digest "
        f"was replaced by a floating tag, which is an unpinning and must be reverted."
    )

for img in sorted(set(expected) & set(found)):
    exp, act = expected[img], found[img]

    missing = REQUIRED - set(exp)
    surplus = set(exp) - REQUIRED
    if missing:
        failures.append(f"{img}: baseline entry is missing required field(s) {sorted(missing)}.")
    if surplus:
        failures.append(
            f"{img}: baseline entry carries unrecognised field(s) {sorted(surplus)}. A field "
            f"nothing reads is a field that looks enforced while doing nothing."
        )
    if missing:
        continue

    if not DIGEST.match(str(exp["digest"])):
        failures.append(f"{img}: baseline digest {exp['digest']!r} is not sha256 plus 64 hex.")

    # One digest across every occurrence. This is the mirror check.
    if len(act["digests"]) > 1:
        failures.append(
            f"{img} is pinned to {len(act['digests'])} different digests across its "
            f"occurrences: {act['digests']}. The copies have diverged -- reconcile them, and if "
            f"one of them is a committed scan report, re-run the scan rather than editing the "
            f"digest by hand."
        )
    elif act["digests"][0] != exp["digest"]:
        failures.append(
            f"{img} is pinned to {act['digests'][0]} but the baseline records "
            f"{exp['digest']}. If the pin moved deliberately, re-verify it with "
            f"'docker buildx imagetools inspect' and update the baseline in this same commit."
        )

    exp_tag = exp["tag"]
    if exp_tag is None:
        if act["tags"]:
            failures.append(
                f"{img}: baseline says tagless but the repository pins it as "
                f"{act['tags'][0]}@<digest>. Record the tag or drop it, but do not leave the "
                f"two disagreeing."
            )
    else:
        if act["tagless"]:
            failures.append(
                f"{img}: baseline records tag {exp_tag!r} but at least one occurrence carries "
                f"no tag. Note the field is mandatory-but-nullable on purpose -- set it to null "
                f"if tagless is intended."
            )
        if act["tags"] and act["tags"] != [exp_tag]:
            failures.append(
                f"{img}: baseline records tag {exp_tag!r}, repository carries {act['tags']}."
            )
        if exp_tag == "latest":
            failures.append(
                f"{img}: pinned beside the tag 'latest'. The digest still governs what runs, so "
                f"this is not a live hazard, but it is a pin whose author was thinking about a "
                f"tag -- name the version instead."
            )

    if sorted(act["occurrences"]) != sorted(exp["occurrences"]):
        extra = sorted(set(act["occurrences"]) - set(exp["occurrences"]))
        gone = sorted(set(exp["occurrences"]) - set(act["occurrences"]))
        if extra:
            failures.append(
                f"{img} now appears in {extra}, which the baseline does not list. Every place a "
                f"digest is written is a place it can go stale -- list it, or remove the "
                f"duplication."
            )
        if gone:
            failures.append(
                f"{img} no longer appears in {gone}, which the baseline still lists. If the "
                f"reference moved or was deleted, update occurrences in this same commit."
            )

if failures:
    print("=== DRIFT DETECTED ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print("  no drift against the baseline")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Resolving each pinned digest at the registry ==="

python3 - "$baseline" "$workdir/images.txt" <<'PY'
import json
import sys
from pathlib import Path

expected = json.loads(Path(sys.argv[1]).read_text())["containers"]
Path(sys.argv[2]).write_text(
    "".join(f"{img}\t{e['digest']}\t{e['tag'] or ''}\n" for img, e in sorted(expected.items()))
)
PY

while IFS=$'\t' read -r image digest tag; do
    [[ -n "$image" ]] || continue
    slug="${image//\//__}"
    echo "  inspecting ${image}@${digest:0:19}..."

    if ! docker buildx imagetools inspect --raw "${image}@${digest}" \
            > "$workdir/${slug}.manifest.json" 2> "$workdir/${slug}.manifest.err"; then
        echo "FATAL: could not resolve ${image}@${digest} at the registry."
        sed 's/^/       /' "$workdir/${slug}.manifest.err"
        echo "       Treating this as 'could not run' rather than as drift: an unreachable"
        echo "       registry, an anonymous rate limit or an offline machine all look identical"
        echo "       from here, and none of them is evidence the pin is wrong. Re-run when the"
        echo "       network is available. If the digest genuinely no longer exists, that IS a"
        echo "       finding -- but confirm it by hand before touching the baseline."
        exit 2
    fi

    # For a single-platform manifest the platform lives in the config blob rather than the
    # manifest, so it has to be resolved separately. Only the '{{json .Image}}' form works --
    # '{{.Image.Os}}' errors with "can't evaluate field Os in type *v1.Image".
    docker buildx imagetools inspect --format '{{json .Image}}' "${image}@${digest}" \
        > "$workdir/${slug}.image.json" 2>/dev/null || echo 'null' > "$workdir/${slug}.image.json"
done < "$workdir/images.txt"

echo ""
echo "=== Registry shape against the baseline ==="

python3 - "$baseline" "$workdir" <<'PY'
import json
import sys
from pathlib import Path

baseline_path, workdir = sys.argv[1], sys.argv[2]
expected = json.loads(Path(baseline_path).read_text())["containers"]
found = json.loads(Path(workdir, "found.json").read_text())

ATTESTATION = "vnd.docker.reference.type"
failures = []


def platform_string(p):
    s = f"{p.get('os', '?')}/{p.get('architecture', '?')}"
    if p.get("variant"):
        s += "/" + p["variant"]
    return s


for image in sorted(expected):
    exp = expected[image]
    slug = image.replace("/", "__")
    manifest = json.loads(Path(workdir, f"{slug}.manifest.json").read_text())

    media = manifest.get("mediaType", "(absent)")
    if media != exp["mediaType"]:
        failures.append(
            f"{image}: registry serves mediaType {media!r} at this digest, baseline records "
            f"{exp['mediaType']!r}. A digest is immutable, so this means the baseline was "
            f"derived from a different reference than the one pinned -- most likely a "
            f"per-platform sub-manifest instead of the index."
        )

    children = manifest.get("manifests")
    attestations = 0
    if children is None:
        # Single-platform manifest. Resolve os/architecture from the config blob.
        cfg = json.loads(Path(workdir, f"{slug}.image.json").read_text())
        if not isinstance(cfg, dict) or not cfg.get("architecture"):
            failures.append(
                f"{image}: is a single-platform manifest and its platform could not be resolved "
                f"from the config blob, so the baseline's platforms list cannot be verified."
            )
            platforms = []
        else:
            platforms = [platform_string({
                "os": cfg.get("os"),
                "architecture": cfg.get("architecture"),
                "variant": cfg.get("variant"),
            })]
    else:
        platforms = []
        for child in children:
            if (child.get("annotations") or {}).get(ATTESTATION) == "attestation-manifest":
                attestations += 1
                continue
            p = child.get("platform") or {}
            if p.get("os") == "unknown":
                continue
            platforms.append(platform_string(p))

    if sorted(platforms) != sorted(exp["platforms"]):
        failures.append(
            f"{image}: registry reports platforms {sorted(platforms)}, baseline records "
            f"{sorted(exp['platforms'])}."
        )

    if attestations != exp["attestations"]:
        failures.append(
            f"{image}: {attestations} buildx attestation manifest(s) at this digest, baseline "
            f"records {exp['attestations']}. Zero is not a finding in itself -- it is simply "
            f"less to verify against, and provenance proves origin, never benignity. A CHANGE "
            f"is the finding, because it means this is not the artefact that was reviewed."
        )

    # The ADR-055 trap, encoded. Gated only where it bites: a container CI runs on
    # ubuntu-latest, which is amd64. A local-only image is free to be arm64-only.
    runs_in_ci = any(o.startswith(".github/workflows/") for o in found.get(image, {}).get("occurrences", []))
    if runs_in_ci and "linux/amd64" not in platforms:
        failures.append(
            f"{image} is referenced from .github/workflows/ but linux/amd64 is not reachable at "
            f"this digest (platforms: {sorted(platforms)}). GitHub's ubuntu-latest runners are "
            f"amd64, so this pin will fail there with 'no matching manifest for linux/amd64'. "
            f"This is ADR-055's trap 2: take the top-level 'Digest:' from "
            f"'docker buildx imagetools inspect {image}:<tag>', never a per-platform child."
        )

if failures:
    print("=== DRIFT DETECTED ===")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

for image in sorted(expected):
    e = expected[image]
    print(f"  {image}: {e['mediaType']}")
    print(f"    platforms {sorted(e['platforms'])}, "
          f"{e['attestations']} attestation manifest(s) -- as recorded")
print("  no drift against the baseline")
PY

# ---------------------------------------------------------------------------------------------
echo ""
echo "=== Tag drift (reported, never gated) ==="

reported_any=0
while IFS=$'\t' read -r image digest tag; do
    [[ -n "$image" ]] || continue
    if [[ -z "$tag" ]]; then
        echo "  ${image}: tagless pin, nothing to compare"
        continue
    fi
    reported_any=1
    slug="${image//\//__}"
    if ! docker buildx imagetools inspect "${image}:${tag}" > "$workdir/${slug}.tag.txt" 2>&1; then
        echo "  ${image}:${tag}: could not resolve the tag -- reported, not failed. A tag being"
        echo "    deleted upstream is exactly the event a digest pin makes survivable."
        continue
    fi
    current="$(awk '/^Digest:/ { print $2; exit }' "$workdir/${slug}.tag.txt")"
    if [[ "$current" == "$digest" ]]; then
        echo "  ${image}:${tag} still resolves to the pinned digest"
    else
        echo "  ${image}:${tag} now resolves to ${current}"
        echo "    The pin is ${digest}, so nothing changed about what runs. A mutable tag moving"
        echo "    is ordinary upstream behaviour and is the whole reason the digest is here."
        echo "    Upgrading is a deliberate, reviewed edit -- and for sonarqube or the scanner it"
        echo "    invalidates the committed SonarQube baselines, so re-derive them in the same"
        echo "    commit. Never bump a pin to make this line quieter."
    fi
done < "$workdir/images.txt"
[[ "$reported_any" == "1" ]] || echo "  (every pin here is tagless, so there is no tag to drift)"

echo ""
echo "=== PASS: every pinned container is declared, correctly shaped and consistently recorded ==="
