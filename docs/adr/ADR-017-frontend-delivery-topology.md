# ADR-017: Front-End Delivery via Caddy on a Single Origin

**Status:** Accepted
**Date:** 2026-08-05
**Deciders:** @tech-lead, @architecture-guardian, @ui-builder

## Context

ADR-009 chose React, TypeScript and Vite with the GOV.UK Design System for the front end. It
did not say how the built assets reach a browser, and it did not choose a reverse proxy. Nothing
else in this repository has either: the only mention of a proxy anywhere is a passing remark in
ADR-012 that a domain plus a reverse proxy would be the fix for the accepted lack of TLS.

That gap has been quietly load-bearing. The product requirements document has carried an
assumption naming Caddy since it was written, labelled openly as a guess with no decision behind
it, and a story description repeated the guess as though a decision record had made it. This ADR
closes the gap so the front-end scaffold is built against a decision rather than an assumption.

Two things constrain the choice before any preference enters.

**The owner already chose a separate container over bundling.** During requirements discovery the
options were folding the Vite build into the application jar's static resources, or serving it
from its own container behind a proxy. The second was chosen.

**ADR-014 already recorded a proxy requirement.** Server-sent events need response buffering
disabled or the browser receives nothing until the stream closes. That ADR noted nginx needs an
explicit directive for this and Caddy needs none, and left the observation as a constraint to
carry into this decision.

## Decision

### Caddy, in its own container, serving the assets and proxying the API on one origin

Caddy publishes port 80. It serves the built front-end assets at `/`, and reverse-proxies
`/api/*` and `/health` to the application container on port 8080 over the Compose network. The
application container publishes no host port at all.

**The single origin is the point, not the TLS.** With the browser talking to exactly one origin,
cross-origin request handling never enters the system: no `@CrossOrigin`, no CORS configuration
class, no preflight requests, no allow-list of front-end origins to keep in step with a deployment
address that changes. That is a whole category of bug and configuration this application will now
never have. Serving the front end from a different port or host would have required CORS on day
one and forever after.

### Caddy over nginx

- **Server-sent events need no directive.** Caddy does not buffer proxied responses by default,
  so the transport ADR-014 chose works through it with no configuration. nginx buffers by default
  and needs `proxy_buffering off` on the streaming route. A default that is correct beats a
  default that is wrong plus a comment explaining why.
- **TLS becomes a one-line change rather than a project.** ADR-012 accepted plain HTTP on a bare
  address, with browsers showing "Not secure". Caddy obtains and renews certificates
  automatically once a real hostname exists. That converts an accepted limitation into a
  single-line edit on the day a domain is bought, instead of a certbot installation, a renewal
  timer and a reload hook.
- **The configuration file is small enough to review.** This repository is maintained largely by
  AI agents. A five-line `Caddyfile` that a reviewer can hold in their head is worth more here
  than nginx's larger surface, most of which we would not use.

nginx was a genuine candidate, not a straw man: it is more widely understood, more widely
documented, and its performance ceiling is far above anything this application will approach. It
loses on the two points above, both of which are specific to what this project actually needs.

### Why a proxy at all, stated honestly

Bundling the Vite output into `src/main/resources/static/` would have been simpler in every
mechanical respect. One image instead of two. One thing to deploy. Same origin for free, with no
proxy to configure, no third container to health-check and no extra hop to debug. Spring Boot
serves static resources perfectly well.

It loses on coupling. Bundled, every front-end change — a wording fix, a colour, a label —
requires a Maven build, a new application image and an application restart, and cannot be shipped
without also reshipping the back end. Separate, the two move independently.

This is a trade, not a free win, and the cost is real: an extra container, an extra image to
build and publish, an extra hop in every request, and one more thing that can be misconfigured.
The owner chose it knowing that.

### Single-page-application routing

Caddy serves `index.html` for any path that does not match a file, so client-side routes survive
a page refresh. Without this, refreshing on any route other than the root returns 404 — which
looks like a broken deployment and is in fact a missing fallback.

### The application port closes

The application container stops publishing a host port. Only Caddy is reachable. Three
consequences follow, and all three are deliberate:

- **The load test now measures the real path.** `k6` targets port 80 through Caddy rather than
  the application directly, because that is the path a user takes. The previously measured
  95th-percentile figure of 5.77 ms described a request that bypassed the proxy and therefore
  described nothing a user will ever experience. The performance baseline is reset rather than
  compared, and the reset is recorded in `docs/performance/TRENDS.md`.
- **The pipeline smoke test goes through Caddy.** Otherwise nothing in the build proves the proxy
  works, and the first evidence would arrive from a browser after a merge.
- **The infrastructure network rules change.** The security group opens 80 and closes 8080. That
  edit belongs to this decision, and lands in `infra/`. It cannot be proven: `terraform validate`
  confirms only that the configuration parses, and no `apply` has ever run against a real account
  (see the ADR-012 amendment).

Debugging the application directly now needs `docker compose exec` or a temporary port mapping.
That is a small, deliberate inconvenience in exchange for local topology matching deployed
topology, which is the same argument ADR-016 made for the container runtime.

### No feature flag on this slice

The story description asks for the proxy to serve a holding page with a flag off and the
application with it on. That flag is not implemented, for the reason ADR-013 already gives: a
flag protects a live production surface, and there is none — nothing is deployed anywhere, and
the only person who can reach this stack is the developer running it. The flag would buy a second
Caddy configuration, a second code path and a doubled verification matrix, all to be deleted
before anyone outside the machine could see either state.

This is the same judgement applied to EOP-6 and accepted then. ADR-013 stands unchanged: flagging
begins when there is a deployed surface to protect.

## Consequences

**Positive:** cross-origin configuration never enters the codebase, because the browser only ever
sees one origin.

**Positive:** the transport chosen in ADR-014 works through the proxy with no configuration,
because Caddy does not buffer by default.

**Positive:** TLS is a one-line change the day a hostname exists, so the limitation ADR-012
accepted has a cheap exit rather than a project attached to it.

**Positive:** front end and back end ship independently, which was the reason for choosing a
separate container.

**Positive:** the load-test figure will describe the path a user actually takes, for the first
time.

**Negative — one more container, one more image, one more hop.** The stack grows from two
services to three. The pipeline builds and publishes a second image. A failed request now has
two places to fail and two sets of logs to read. This is the cost of the decoupling above and is
not recovered anywhere.

**Negative — the performance baseline is discarded, not migrated.** The 5.77 ms 95th percentile
cannot be compared with anything measured after this change. Any apparent regression at the next
measurement is a change of measurement position, not of performance, and the record has to say so
or it will be misread later.

**Negative — the infrastructure change is unverifiable today.** Opening 80 and closing 8080 in
the security group is written but has never been applied. If it is wrong, the failure surfaces on
the first deployment, which is exactly the class of risk the ADR-012 amendment already documents.

**Negative — direct access to the application is gone locally.** Anyone used to curling port 8080
will find nothing listening. Documented, but it will still surprise someone once.

**Neutral — accessibility is best effort and the gap is recorded.** The GOV.UK Design System
provides accessible defaults, and using it is a real advantage over hand-rolled markup. Full WCAG
2.2 AA conformance is **not** claimed: no audit has been run, no assistive technology has been
tested, and the requirements document already records this as an accepted gap rather than an
oversight.

**Neutral — the `Caddyfile` is committed and mounted, not generated.** It is configuration that
belongs under review like any other, and a file that can be read in the repository beats a
heredoc buried in a bootstrap script.

**Neutral — this changes nothing about who can reach the application.** It still runs on one
machine, reachable only by its operator. The proxy is a step towards public access, not public
access itself.

## Related

- [ADR-009: Front-End Technology Stack](ADR-009-frontend-react-typescript.md) — chose React,
  TypeScript, Vite and the GOV.UK Design System, but not how the assets are served
- [ADR-014: Real-Time Transport](ADR-014-realtime-transport.md) — recorded the response-buffering
  constraint that this decision resolves
- [ADR-012: Deployment Target](ADR-012-deployment-target.md) — accepted plain HTTP on a bare
  address; this decision gives that limitation a cheap exit
- [ADR-013: Feature Flags](ADR-013-feature-flags.md) — why this slice ships unflagged
- [ADR-016: Local Container Runtime](ADR-016-local-container-runtime.md) — the runtime this stack
  is verified on
- [Product requirements](../requirements/PRD-eop-card-game.md) — the assumption this decision
  replaces
