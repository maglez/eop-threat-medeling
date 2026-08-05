# ADR-012: Deployment to a Single EC2 Instance with Terraform

**Status:** Accepted (deployment deferred 2026-08-05 — see Amendments)
**Date:** 2026-08-03
**Deciders:** @tech-lead, @devops-engineer

## Context

Until now this repository had no deployment of any kind. `CHANGELOG.md` recorded the
absence plainly: "Deployment: no target, mechanism or infrastructure-as-code has been
decided or built." CI compiled the code, ran the tests and uploaded a jar. Nothing
ever started the artifact, so the walking skeleton of ADR-002 was incomplete by the
definition the Product Owner agent uses — compile, test, build **and deploy**.

Two constraints shaped the decision, both stated by the project owner:

1. The proof of concept must be demonstrable. "I want to be able to tell people to go
   to an IP and see the application working."
2. It must be **extremely easy to migrate to a company AWS account later**. This is
   the dominant constraint. It is also precisely the problem infrastructure-as-code
   exists to solve, which is why an earlier position of "defer IaC until the account
   is real" was reversed.

A third constraint is financial rather than technical: this is a personal account
funded personally, so managed services are avoided where a container will do.

The starting position was hostile to verification. There are no usable AWS credentials
on the development machine — `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are
deliberately blank so that Bedrock authenticates via a bearer token, and
`~/.aws/credentials` is malformed in a way that breaks every `aws` invocation. The
machine is also corporate-managed, and using corporate credentials for a personal
project would be a governance problem regardless of whether it worked. Everything in
this ADR is therefore authored to be correct by construction and validated without
credentials; the first `apply` is the project owner's to run.

## Decision

### The container image is the portability boundary

The deployment unit is an OCI image, not a machine configuration and not a jar. The
same image runs on a laptop, on this EC2 instance, and on ECS, Fargate, App Runner,
Beanstalk or EKS later without being rebuilt. Everything wrapped around it —
`compose.app.yml`, the user-data script, the Terraform — is thin, cheap to discard,
and explicitly **not** expected to survive a platform change.

The image is published to **GHCR, not ECR**. This is the single largest migration
win available: a registry inside the AWS account moves with the account, whereas GHCR
belongs to the repository. Migration therefore does not involve moving, re-tagging or
re-pushing any image. The repository is public and the package is public, so the pull
needs no credentials on the instance.

Consequently the instance has **no IAM instance profile at all**. It makes zero AWS
API calls. Least privilege by having no privileges.

### One EC2 instance running Docker Compose

A `t3.small` (2 GB) running Amazon Linux 2023, with the application and PostgreSQL as
two containers from the same `compose.app.yml` that runs locally. `t3.micro` was
considered and rejected: 1 GB for a JVM with Hibernate plus PostgreSQL forces
`MaxRAMPercentage` tuning, `shared_buffers` trimming and a swap file, and the first
OOM-kill would present as a mysterious crash. Doubling a small number to delete a
class of problem is a good trade.

**PostgreSQL runs in a container on a separate encrypted EBS volume**, not in RDS.
RDS is the correct answer for anything real and the wrong answer for a personal proof
of concept. The separate volume is the important half of this: data on the root volume
is destroyed by instance termination, which is the most common way a proof of concept
loses its database, and a snapshot of a dedicated volume *is* a complete migration
path. The volume carries `prevent_destroy`.

PostgreSQL publishes **no port at all** — not to the internet, not to the host. It is
reachable only from inside the Compose network. There is deliberately no security
group rule for it.

### Terraform, from the start

Terraform rather than OpenTofu, matching what the DevOps agent definition already
names. The HCL is identical, so this is reversible in an afternoon if the BUSL licence
becomes a problem.

Portability is enforced by what the configuration refuses to contain:

- **No AMI ids.** Amazon Linux 2023 is resolved from the public SSM parameter, because
  AMI ids are region-scoped and a pinned id fails silently in another region.
- **No account ids, ARNs or CIDRs inlined.** All variables.
- **No console clicks.** Anything created by hand would not exist in the new account
  and would not be remembered.

A **dedicated VPC** is created rather than using the default one. It costs five extra
free resources and removes a dependency on a default VPC that enterprises routinely
delete — which is exactly the account this is meant to migrate into.

**State is local.** This is a deliberate deviation from the DevOps agent's standard of
S3 with DynamoDB locking. One operator, one laptop, no CI apply, and no high-value
secret in state given that the database is unreachable from outside the Compose
network. Remote state also does not serve the migration goal: a new account needs
fresh state either way. The trigger to change is a second operator or a CI job that
applies, at which point `terraform init -migrate-state` moves it without recreating
anything.

**Terraform is not the deployment tool.** It sets the initial image reference. Routine
deploys pull a new tag and restart the stack over SSH; the `deploy_command` output
spells this out. Changing `var.app_image` also works, by instance replacement, which
is safe because the data is on its own volume and the Elastic IP re-associates.

### Access by SSH keypair, not Session Manager

Chosen by the project owner against a recommendation of SSM Session Manager. Recorded
here with its consequences rather than glossed, because they are real:

- Port 22 is open on a public IP. The `ssh_ingress_cidrs` variable has **no default**
  and validation **refuses `0.0.0.0/0`** outright, so the operator must name their own
  address. That is the mitigation; it is not the same thing as not being exposed.
- A private key exists and must be managed.
- **If CI is ever made to deploy over SSH, that private key becomes a repository
  secret, and this repository's zero-secrets property is destroyed.** That property is
  currently real — `gh secret list` and `gh variable list` are both empty, and the new
  image job publishes to GHCR using only the built-in `GITHUB_TOKEN`. Automated
  deployment should therefore be reached via SSM, or via a pull-based agent on the
  instance, not by adding an SSH key to Actions.

Attaching `AmazonSSMManagedInstanceCore` is the one-line change that reverses this
later, and is noted in `infra/compute.tf` at the point where it would go.

### Bare IP over HTTP, no TLS

The demo URL is `http://<elastic-ip>:8080`. Public certificate authorities do not
issue certificates for bare IP addresses on the ordinary path, so TLS would require a
domain. Browsers will show "Not secure". This is accepted for a proof of concept, with
the acknowledgement that it is a slightly awkward look for a threat-modelling
application. A domain of a few pounds a year plus a reverse proxy is the fix when it
matters.

### `server.address` changed in the production profile

`application-prod.yml` bound to `127.0.0.1`. In a container that is not isolation, only
unreachability: `docker run -p 8080:8080` would connect-refuse and present as a
startup failure. It is now `0.0.0.0`, and isolation comes from the security group and
the published ports, which is where it belongs once the deployment unit is a container.

The container runs with `SPRING_PROFILES_ACTIVE=prod` rather than a third `local`
overlay profile, so local and AWS execute byte-identical configuration and local
behaviour predicts deployed behaviour.

**Accepted cost: there is no Swagger UI in the container.** The production profile
disables `springdoc.api-docs` and `springdoc.swagger-ui` on purpose. It remains
available via `./mvnw spring-boot:run` on the default profile. This is a decision, not
a defect, and should not be "fixed" later.

### Rejected alternatives

- **LocalStack.** Its EC2 support is API-level mocking: it would accept `RunInstances`
  and never run the JVM. Terraform written against it would prove that the HCL parses,
  not that the application deploys — the exact illusion this work exists to remove.
- **Kubernetes, locally or as EKS.** An EKS control plane is roughly $73/month before
  a single node, which contradicts the cost constraint, and both options are vast
  overkill for one process and one database.
- **Local-only Docker Compose with no cloud at all.** Considered and briefly chosen,
  then abandoned by the project owner in favour of something demonstrable. The work
  was not wasted: the Dockerfile and Compose file authored for it *are* the deployment
  artifact.
- **RDS.** Correct for production, disproportionate here, and it would put the data
  inside the account being migrated away from.

## Consequences

**Positive:** the walking skeleton is finally complete end to end — the pipeline
compiles, tests, builds an image, **starts it against a real PostgreSQL and asserts
that `/health` returns `OK`**, then publishes it. Nothing before this proved the
artifact boots.

**Positive:** account migration is a variable change and an `apply`. Set the profile
and region, restore a snapshot of the data volume, apply. No image move, no AMI
lookup, no hand-built resource to rediscover.

**Positive:** no repository secrets are introduced. GHCR publishing uses the built-in
`GITHUB_TOKEN` with `packages: write` scoped to the one job that needs it.

**Positive:** the instance holds no AWS credentials, requires IMDSv2, and exposes
exactly two ports.

**Negative — Compose is not AWS infrastructure and never becomes it.** There is no
autoscaling, no load balancer, no zero-downtime deploy, and a restart is visible to
anyone watching. The orchestration layer is throwaway by design; only the image,
the Dockerfile and the registry survive a platform change.

**Negative — a single instance is a single point of failure**, and
`user_data_replace_on_change = true` means editing the bootstrap script replaces it.
That default is `false`, which silently ignores bootstrap edits, and being explicit
about replacement is the lesser evil. Data survives because it is on its own volume.

**Negative — the PostgreSQL password is generated by Terraform and therefore sits in
local state.** It is generated rather than supplied because its value must be *stable
across instance replacement*: the data volume survives, so a regenerated password
would fail to authenticate against the existing database. The exposure is low given
the database publishes no port, but it is a real reason the state file must not be
committed, and `infra/.gitignore` enforces that.

**Negative — a residual failure mode in the bootstrap.** The data volume is mounted
with `nofail` so a mount failure does not brick the boot. The consequence is that
PostgreSQL would then initialise an empty database on the root volume. Nothing is
lost — the real data is still on EBS — but the symptom looks exactly like data loss,
so `mountpoint /var/lib/eop` is the first thing to check.

**Negative — AWS bills every public IPv4 address**, including an Elastic IP that is
not attached to anything. A stable demo address is not free, and `terraform destroy`
will refuse the data volume because of `prevent_destroy`, so tearing down completely
takes a deliberate extra step.

**Neutral — the build architecture is a trap worth naming.** The development machine
is Apple Silicon, so a local `docker build` produces an arm64 image that cannot run on
a `t3.small`. CI builds `linux/amd64` explicitly and is the only thing that publishes
a deployable image. Local builds remain useful for verification and must never be the
source of a deployed tag.

**Neutral — the image job is not a required status check.** It lands as an ordinary
job so its reliability can be observed before a first-run flake is allowed to block
every merge. Promoting it is a separate branch-protection change.

**Neutral — the volume is discovered by NVMe serial rather than device name.** On
Nitro instances the attach-time name (`/dev/sdf`) is not the kernel name
(`/dev/nvme1n1`). AWS sets the EBS NVMe serial to the volume id with hyphens stripped,
so the path is derived rather than guessed. Guessing wrong would format the wrong disk.

**Neutral — this ADR cannot be verified end to end yet.** The Terraform is validated
and the bootstrap script is syntax-checked, but no `apply` has run because no personal
AWS account exists yet. `infra/README.md` lists what the project owner must do first;
the "Implemented?" column in the ADR index reflects that honestly.

## Amendments

**2026-08-05 — deployment deferred; the same artifacts now run locally first.**
The decision above is left as written because none of it turned out to be wrong.
The OCI image remains the portability boundary, GHCR remains the registry, Compose
remains the orchestration layer, and Terraform remains the mechanism for the AWS
target. What changed is **timing, not design**: the owner deferred creating a
personal AWS account, so the stack now runs on the development machine using the
*same* `Dockerfile`, the *same* `compose.app.yml`, the *same* `prod` profile and
the *same* `docker compose -f compose.app.yml up -d` command that the user-data
script in `infra/templates/user-data.sh.tftpl` runs on the instance.

That the local pivot required **no change to any artifact named in this ADR** is
the strongest available evidence that the portability argument in the Decision
section was sound. See [ADR-016](ADR-016-local-container-runtime.md) for the
runtime that made it possible.

`infra/` is untouched by the deferral. It remains `terraform validate`-clean and
**has never been applied**, so every claim in this document about instance
behaviour — the AL2023 AMI resolving from the SSM parameter, user-data running to
completion, the NVMe-serial volume discovery, the anonymous GHCR pull, the
`nofail` mount hazard — is still unverified against a real AWS API. The five
owner prerequisites listed in `infra/README.md` are all still outstanding.
[EOP-7](https://maglez.atlassian.net/browse/EOP-7) stays open and unstarted; it
is postponed, not withdrawn.

## Related

- [ADR-016: Colima as the local container runtime](ADR-016-local-container-runtime.md)
  — how the same artifacts run on a developer machine, and the arm64/amd64 caveat
- [ADR-002: Spring Boot Walking Skeleton](ADR-002-spring-boot-bootstrap.md) — the
  skeleton this completes
- [ADR-008: Database migrations with Liquibase](ADR-008-database-migration-liquibase.md)
  — runs at container startup against the containerised PostgreSQL
- `infra/README.md` — prerequisites, apply and deploy recipes, and the account
  migration procedure
- `Dockerfile`, `.dockerignore`, `compose.app.yml` — the portable artifact
- `.github/workflows/ci.yml` — build, smoke test and publish
