# ADR-016: Colima as the Local Container Runtime

**Status:** Accepted
**Date:** 2026-08-05
**Deciders:** @tech-lead, @devops-engineer

## Context

ADR-012 chose an OCI container image as the portability boundary and Docker
Compose as the orchestration layer, on the reasoning that the same image and the
same Compose file would run unchanged locally and on EC2. That reasoning held.
What did not hold is the assumption that a container runtime would be available
on the development machine.

Until this decision, **there was no container engine on the machine at all**.
Only the Homebrew `docker` CLI formula was installed — a client with nothing to
talk to. The consequence was that four verification gates recorded in the EOP-4
work could never be executed outside CI: building the image, running it,
starting the full stack against real PostgreSQL, and running the k6 load test
against the container rather than a development-mode JVM. The ninety-fifth
percentile latency figure quoted in `docs/performance/TRENDS.md` and asserted by
`test/k6/config/options.js` had therefore never been measured against the
artifact that ships.

Two constraints ruled out the obvious answer. The machine is centrally managed
and the developer has **no administrator rights**; Docker Desktop is distributed
as a macOS application bundle that installs a privileged helper, so it cannot be
installed without a password. Independently, Docker Desktop's licence requires a
paid subscription for commercial use in organisations above a size threshold,
which this one exceeds — the same class of governance problem as the corporate
AWS credentials discussed in ADR-012. Any runtime had to be installable as an
unprivileged Homebrew formula.

## Decision

### Colima, not Podman and not Docker Desktop

Install **Colima** (MIT) via `brew install colima`. It is a plain formula whose
only dependency is `lima`, installs nothing into `/Applications`, registers no
privileged helper, and prompts for no password.

The deciding argument is **socket compatibility, not preference**. Colima
exposes a Docker-API socket, so the existing `docker` CLI works against it
unchanged and the command that starts the stack locally is character-for-character
the command CI runs and the command the EC2 bootstrap script runs:

```
docker compose -f compose.app.yml up -d
```

Podman was a genuine candidate — Apache-2.0 and GPL-3.0-or-later, zero runtime
dependencies, equally unprivileged. It was rejected because reaching the same
place requires either `podman compose` or aliasing the socket, and the resulting
divergence between local, CI and deployed command strings is exactly where
mistakes hide. Sameness was worth more than any other difference between the two.

On Apple Silicon, Colima is started with the Apple Virtualization framework
rather than QEMU:

```
colima start --vm-type=vz --cpu 4 --memory 6 --disk 60
```

`--vm-type=vz` is passed explicitly because QEMU is not installed and would have
to be added; `vz` ships with macOS. Six gigabytes rather than the two-gigabyte
default because the JVM, PostgreSQL, InfluxDB and Grafana do not fit comfortably
in two. Mirroring the t3.small's two gigabytes was considered and rejected: we
are not deploying there today, and an out-of-memory kill during debugging
teaches the wrong lesson.

### Three formulae are required, not one

The Homebrew `docker` formula ships **neither Compose nor Buildx**. Both are
separate formulae, and both install into `/opt/homebrew/lib/docker/cli-plugins`,
which the CLI does not search unless `cliPluginsExtraDirs` is set in
`~/.docker/config.json`:

```
brew install colima docker docker-compose docker-buildx
```

`k6` is a fourth formula, needed for the load test and likewise absent.

### The uninstalled Docker Desktop had to be cleaned up first

Docker Desktop had previously been **run from a mounted disk image** rather than
installed, and left behind a full `~/.docker` profile: fifteen plugin symlinks
pointing into `/Volumes/Docker/Docker.app/...`, a `credsStore` naming a
credential helper that is not on `PATH`, and a `currentContext` of
`desktop-linux` pointing at a dead socket.

This is worth recording because of how it fails. The CLI finds a `docker-compose`
symlink, cannot execute it, and reports **`docker: unknown command`** — which
reads as "Compose is not installed" when in fact a broken Compose *is*
installed and is shadowing the working one. Fourteen dangling symlinks were
removed, `config.json` was reduced to `auths` plus `cliPluginsExtraDirs`, and
the `desktop-linux` context was deleted.

### CI remains the authority for the deployable image

This is the honest cost of the decision. The development machine is **arm64**;
the EC2 instance and the published GHCR image are **amd64**. A locally built
image is therefore *not* the artifact that deploys. `compose.app.yml` already
defaults `APP_IMAGE` to `eop-threat-medeling:local`, so no configuration changes
— but the local image and the published image are different builds of the same
source and must not be conflated.

Colima installs binfmt handlers during `start`, so `docker build --platform
linux/amd64` does work locally under QEMU emulation. That softens the divergence
without removing it: emulated builds are slow and are not the default, so the
default remains architecture-local and CI remains the authority.

## Consequences

**Positive:** every verification gate can now be executed on a developer
machine. Gate 4 has run for the first time: p95 5.77 ms against a 200 ms
threshold, max 27.33 ms against 1000 ms, zero of 221 requests failed, 663 of 663
checks passed. That is the first time the ninety-fifth percentile figure in this
repository has described the real artifact rather than a development-mode JVM.

**Positive:** the Dockerfile is now proven on both architectures. The
`eclipse-temurin:21-jdk-alpine` and `21-jre-alpine` base images, the non-root
user, and the busybox `wget` healthcheck all work on arm64 as well as amd64. No
fallback to `-noble` was needed on either.

**Positive:** the card catalogue delivered in EOP-6 now runs against real
PostgreSQL outside CI, exercising the first Liquibase changeset, the JPA
persistence adapter and the RFC 9457 handler together on a developer machine.
`depends_on: condition: service_healthy` sequences the app behind the database
correctly, verified by observing `eop-postgres Healthy` before `eop-app Starting`.

**Negative — the local image is not the deployed image.** Stated above; the
mitigation is that CI builds and publishes, and nothing deploys from a laptop.

**Negative — the runtime is a virtual machine with a real cost.** Six gigabytes
of RAM and sixty of disk are committed while it runs, and it must be started
explicitly (`colima start`) after a reboot unless registered as a service. Docker
Desktop users do not think about this; here it is visible.

**Negative — Compose project names had to be set explicitly.** Both Compose
files previously derived their project name from the directory, so each stack
reported the other's containers as orphans and `docker compose down
--remove-orphans` on either file would have destroyed the other stack. Fixed by
adding `name: eop-app` and `name: eop-monitoring`. Anyone with volumes from
before this change will find them orphaned under the old project name.

**Neutral — the k6 metrics pipeline is broken, and this decision did not fix
it.** Running the load test revealed that measurements have never reached
InfluxDB, so the Grafana dashboard has always been empty. Three independent
causes were identified: `.env.example` shipped `INFLUXDB_URL=http://influxdb:8086`,
a *container* hostname that cannot resolve from the host where k6 runs and which
also drops the `/k6` database path (fixed here, because it is a tracked file);
direnv exports the stale value into an already-running shell so editing `.env`
appears to have no effect; and the monitoring stack enables HTTP authentication
while the k6 output URL carries no credentials — a direct credentialed write
still returns 401, so why the configured admin credentials are refused is
unresolved. `test/k6/run.sh` is *not* at fault; its default is correct and it
passes the variable through untransformed.

Notably k6 logs one `Couldn't write stats` error per flush interval — 41 in a
40-second run — but **exits 0**, so the threshold gate passes and anything
checking only the exit code never notices. Loud in the log, silent in the exit
code. Repair belongs in its own story; the load test's own JSON and summary
output files are the real evidence and they are written correctly.

**Neutral — this does not give anyone else access.** The stack binds to
`127.0.0.1`. Multiplayer is simulated with several browser tabs, which works
because ADR-015 chose a per-tab credential; a duplicated tab inherits it and
would arrive as the same player, and the game needs three players minimum.
Sharing a URL with colleagues remains unsolved and would need its own decision.

## Related

- [ADR-012: Deployment to a single EC2 instance with Terraform](ADR-012-deployment-target.md) — the image and Compose file this reuses unchanged; amended alongside this decision
- [ADR-015: Player identity](ADR-015-player-identity.md) — why per-tab credentials make browser-tab multiplayer simulation work
- `compose.app.yml`, `Dockerfile` — the artifacts that did not have to change
- `SETUP.md`, `docs/devops/local-development.md` — the per-clone steps this adds
- [EOP-16](https://maglez.atlassian.net/browse/EOP-16) — the story
