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
request path plus an operational component to run alongside the application and
its database on a single developer machine.

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
  With one process and no load balancer that means a few seconds of
  downtime, which is acceptable for a proof of concept and is the same
  interruption every deployment already causes.
- **Flags are set as environment variables**, so a flag can be
  flipped without rebuilding the image — set `EOP_FEATURES_SESSION_LIFECYCLE=true`
  in the environment the container is started with and restart the stack with
  `docker compose -f compose.app.yml up -d`.
- **Every flag is off by default.** An unset property reads as disabled, so
  forgetting to configure a flag fails closed rather than releasing early.
- **A flag is deleted once its feature is released.** A flag that outlives its
  rollout is a permanent branch in the code and a permanent second path through
  the tests.

**EOP-6 ships unflagged.** A flag protects a live production surface from a
half-finished feature. There is no live production surface, the endpoint is read
only, and the deck it serves is placeholder data that no client depends on.
Adding a flag there would be ceremony that has to be tested and then removed.

**Flagging starts at EOP-10**, the first story with a user-visible write surface to
protect. From that point every user-visible change arrives behind a flag, as the
product owner's criteria require.

**Rejected: a flag library or service.** Unleash and Flipt solve problems this
project does not have — percentage rollouts, per-user targeting, an audit trail
of who flipped what, and flag changes without a restart. Buying them now would
add a dependency and, in the hosted case, a third component to run alongside the
application and its database on one developer machine. The migration path is open:
flags are read through one configuration properties class, so replacing the
mechanism touches that class and not the call sites.

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
- **Neutral — this decision was recorded before it was needed**, so that the first
  story to want a flag did not have to make it under time pressure. That story
  turned out to be EOP-10 rather than EOP-7.

## Amendments

**2026-08-10 — the deployment premises in this ADR were corrected; the decision
was not changed.** Three statements referred to a `2 GB instance` and to editing
`/opt/eop/.env` on that instance. The EC2 target has been withdrawn
([ADR-012](./ADR-012-deployment-target.md)), so those premises were false. They
have been reworded to describe the local container stack ([ADR-016](./ADR-016-local-container-runtime.md)).

The reasoning is untouched, because none of it depended on the host. A configuration
property still costs nothing, a hosted flag service is still a third process to run
next to the application and its database, and flipping a flag still requires a
restart — a restart of a local container rather than of a remote one, which is if
anything cheaper.

The trigger story also moved. This ADR said flagging starts at EOP-7, the first
live deployment. EOP-7 is closed as superseded and no longer exists as a story, and
the first flag in the codebase was in fact introduced by EOP-10:
`eop.features.session-lifecycle`, which withholds `SessionController` entirely via
`@ConditionalOnProperty`. [ADR-019](./ADR-019-session-lifecycle-and-join-codes.md)
records that, and its own amendment records the one place where this ADR's
prescribed shape did not fit: `@ConditionalOnProperty` is evaluated before any
`@ConfigurationProperties` bean is bound, so a flag that decides whether a bean
exists cannot also be read through a typed properties class.

**2026-08-13 — a second flag is owed, and it is owed by EOP-14 Slice C2 rather than
Slice C1.** `.opencode/rules/feature-flags.md` makes this ADR and
`src/main/resources/application.yml` the flag register and says there is no separate
catalogue, so the second flag belongs here even before it exists. It does not exist
yet. `eop.features.trick-play` will withhold the deal, play and resolve-trick routes,
and [ADR-023](./ADR-023-deal-remainder-and-turn-order.md) makes it a **merge
precondition** for Slice C2: defaulted to `false`, recorded here, and covered by an
off-position test that asserts the bean is absent as well as the routes answering 404.

**2026-08-14 — `eop.features.trick-play` now exists, and what it withholds grew twice.**
Slice C2 created it gating three use-case beans and no route, because that slice shipped
no route to gate; the off-position test could therefore only assert bean absence, and the
"routes answering 404" half of the clause above was unwritable. EOP-14 Slice D discharges
it. The flag now withholds **four use-case beans and one controller**: `DealHandsUseCase`,
`ReadOwnHandUseCase`, `PlayCardUseCase`, `ResolveTrickUseCase` and `TrickController`, whose
four routes — `POST /{sessionId}/deal`, `GET /{sessionId}/hand`, `POST /{sessionId}/plays`
and `POST /{sessionId}/tricks/current/resolve` — answer the framework's own 404 while it is
off. `TrickPlayDisabledIntegrationTest` now asserts both halves: all five beans absent *and*
all four routes 404, because the status is what a client is promised while the absence is
what pins the mechanism, and a test asserting only the status would pass against a design
this ADR forbids. It stays `false` on merge.

Two things about this flag are worth recording because they are not obvious from its name.
First, `ReadOwnHandUseCase` is a **read** and is gated anyway, which departs from the
"only the writers are gated" principle stated elsewhere in this repository: with the flag
off no hand has ever been dealt, so an ungated read would exist only to answer 409.
Second, the flag cannot be turned on when Slice D merges, and not because of a defect —
`TrickDto` deliberately publishes no answer to whose turn it is, whether the trick is
complete, or which seat leads next, so no client can yet play a game. Turning it on needs
a later slice to add a read that exposes them, and — per @security-auditor on Slice D —
needs [ADR-026](./ADR-026-use-case-observability.md) resolved first, since dealing,
playing and resolving are now reachable and entirely unaudited.

**2026-08-14 — a fail-open condition in the first flag, found reviewing the second.**
`TrickController` was written with `@ConditionalOnProperty(prefix = "eop.features",
name = "trick-play")` and no `havingValue`, which matches any value that is not literally
`false`, while its four use-case beans require `"true"`. `trick-play: yes` would therefore
have registered the routes with none of their use cases and failed at startup — fail-closed,
but a trap for whoever reaches for the flag during an incident. Slice D fixed it to
`havingValue = "true"`. `SessionController` still carries the loose form and is the worse of
the two: its use-case beans are unconditional, so nothing fails the context and
`session-lifecycle: off` — which YAML 1.1 reads as boolean false, and which is the idiom an
operator is likeliest to reach for — would silently **enable** five live routes an operator
believed were off. Fail-open, not cosmetic. It is left for its own commit under its own Jira
key rather than buried in a feature branch; `application.yml` currently sets the literal
`false`, so the shipped default is safe and the exposure is latent operator error.
**Every `@ConditionalOnProperty` on a flag in this repository must carry
`havingValue = "true"`.**

Slice C1 shipped without it, and the reason is worth stating in the register rather
than only in the ADR that argued it. C1 is the persistence layer — five JPA entities,
five Spring Data interfaces, two ports and one adapter — with no controller, no route
and no bean that injects a port. There is nothing for a flag to withhold, so the
off-position test this ADR requires cannot be written: there is no bean to assert
absent and no route to assert 404. **A flag whose off-position test cannot be written
is not containment**, and a `@ConditionalOnProperty` on an adapter nothing injects
would be worse than nothing, because it would produce a passing test that proved only
that an unused bean can be switched off. What contains C1 is structural: the absence
of a caller. From C2 onwards the containment is a flag, which is exactly when the flag
arrives.

Inheriting `eop.features.session-lifecycle` is not an option, for the reason this ADR
gives above: a flag is deleted once its feature is released, so borrowing one that is
due for deletion would tie the removal of a shipped flag to the readiness of an
unshipped feature. The full argument, including the one respect in which C1's
containment is *weaker* than the schema-only slice before it — mapping five tables
makes `ddl-auto: validate` load-bearing, which
`MappedSchemaValidationIntegrationTest` is the answer to — is at the end of
[ADR-023](./ADR-023-deal-remainder-and-turn-order.md). It is not restated here.

## Related

- [ADR-012: Deployment to a Single EC2 Instance with Terraform](./ADR-012-deployment-target.md)
  — the deployment premises this ADR originally borrowed; its EC2 target is withdrawn
- [ADR-016: Colima as the local container runtime](./ADR-016-local-container-runtime.md)
  — where the application actually runs, and therefore where a flag is actually set
- [ADR-019: Session lifecycle and join codes](./ADR-019-session-lifecycle-and-join-codes.md)
  — the first flag in the codebase, and why it is gated but not typed
- [Product requirements](../requirements/PRD-eop-card-game.md)
- [Configuration rules](../../.opencode/rules/configuration.md)
