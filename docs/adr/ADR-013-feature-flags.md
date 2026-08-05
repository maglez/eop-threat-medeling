# ADR-013: Feature Flags via Spring Configuration Properties

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** @tech-lead, @product-owner

## Context

The product owner's acceptance criteria separate "code deployed to production" from
"feature released to users", with a flag between the two. That separation only has
value once there is a production to deploy to. Today there is none: the pipeline
builds and publishes a container image, and `terraform apply` has never run
(ADR-012).

No flag library is on the dependency list. The candidates range from a
configuration property to a hosted service (Unleash, Flipt, FF4J), and the cost
of each is very different: a property costs nothing, a library costs a
dependency and a convergence risk, and a service costs a network hop on the
request path plus an operational component on a single 2 GB instance.

The first story to face this, EOP-6, is a read-only card catalogue. The flag the
backlog originally proposed for it would have gated `GET /api/v1/cards` behind a
`404`, which buys nothing while nothing is deployed and costs a conditional code
path and a doubled test matrix.

## Decision

**Feature flags are Spring configuration properties.** A flag is a boolean
property under `eop.features.*`, read through `@ConfigurationProperties` with
`@Validated`, and gated with `@ConditionalOnProperty` where a whole bean should
disappear or with a plain conditional where behaviour should branch.

Consequences of that shape, stated so they are not discovered later:

- **Flipping a flag restarts the application.** Properties are bound at startup.
  On a single instance behind no load balancer that means a few seconds of
  downtime, which is acceptable for a proof of concept and is the same
  interruption every deployment already causes.
- **Flags are set as environment variables** on the instance, so a flag can be
  flipped without rebuilding the image — `EOP_FEATURES_SOMETHING=true` in
  `/opt/eop/.env` followed by `docker compose up -d`.
- **Every flag is off by default.** An unset property reads as disabled, so
  forgetting to configure a flag fails closed rather than releasing early.
- **A flag is deleted once its feature is released.** A flag that outlives its
  rollout is a permanent branch in the code and a permanent second path through
  the tests.

**EOP-6 ships unflagged.** A flag protects a live production surface from a
half-finished feature. There is no live production surface, the endpoint is read
only, and the deck it serves is placeholder data that no client depends on.
Adding a flag there would be ceremony that has to be tested and then removed.

**Flagging starts at EOP-7**, the story that produces the first live deployment.
From that point every user-visible change arrives behind a flag, as the product
owner's criteria require.

**Rejected: a flag library or service.** Unleash and Flipt solve problems this
project does not have — percentage rollouts, per-user targeting, an audit trail
of who flipped what, and flag changes without a restart. Buying them now would
add a dependency and, in the hosted case, a component to run on a 2 GB instance
alongside the application and its database. The migration path is open: flags
are read through one configuration properties class, so replacing the mechanism
touches that class and not the call sites.

## Consequences

- **Positive:** No new dependency, and nothing new to run or monitor. Flags are
  visible in `application.yml` and overridable per environment by the mechanism
  the deployment already uses. Unit tests set a property rather than stubbing a
  client.
- **Positive:** Reading flags through one typed properties class means the call
  sites do not know how a flag is stored, so a later move to a library or service
  is a change in one place.
- **Negative — a restart is required to flip a flag.** A real kill switch for an
  incident in progress would need something else. Accepted: the application can
  be restarted in seconds, and there are no users to protect from a few seconds
  of downtime.
- **Negative — no audit trail.** Who enabled a flag, and when, is not recorded
  anywhere except the shell history of whoever edited the environment file.
- **Neutral — the flag namespace is a convention, not an enforced rule.** Nothing
  stops a future property from being added outside `eop.features.*`. A reviewer
  catches that, not the compiler.
- **Neutral — no flag exists yet.** This decision is recorded before it is needed
  so that EOP-7 does not have to make it under time pressure while also making a
  first deployment work.

## Related

- [ADR-012: Deployment to a Single EC2 Instance with Terraform](./ADR-012-deployment-target.md)
- [Product requirements](../requirements/PRD-eop-card-game.md)
- [Configuration rules](../../.opencode/rules/configuration.md)
