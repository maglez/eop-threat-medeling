# ADR-035: TLS at Caddy and Security Response Headers

**Status:** Accepted
**Date:** 2026-08-15
**Story:** EOP-21
**Deciders:** @tech-lead, @security-auditor, @devops-engineer, @architecture-guardian

## Context

EOP-10 introduced a bearer-token identity model: a 43-character opaque token is
issued on admission, stored SHA-256 hashed in the database, and replayed in the
`X-EoP-Player-Token` header on every subsequent request. ADR-015 accepted that
this token travels in plaintext and recorded the consequence explicitly:

> *"No credential design survives a plaintext transport. The moment this is
> exposed beyond one machine — a tunnel, a port forward, a colleague on the same
> LAN, a resumed cloud path — the original sentence applies again in full,
> unmodified. TLS is the fix, not a cleverer token."*

The application runs locally under ADR-016, so the immediate exposure is limited
to whatever can read the developer machine's loopback traffic. That is a smaller
set of attackers than a network path, but not an empty one. More importantly, the
ADR-015 warning is forward-looking: any future exposure — a demo on a shared
network, a tunnel to a colleague, a cloud deployment — would immediately make the
token capturable.

Additionally, `DisplayName` deliberately does not escape HTML (delegating to
React), and a display name is broadcast to every other player. There is no
`Content-Security-Policy` to catch the day a consumer stops being React. The
absence of standard security response headers is a latent risk that grows with
every new consumer of the API.

## Decision

**Enable TLS at Caddy using `tls internal` (self-signed certificate from Caddy's
local CA), and add a standard set of security response headers to all responses.**

### TLS

`tls internal` in the Caddyfile instructs Caddy to issue a certificate from its
own local CA. This is the correct choice for a closed demo with no public domain:

- No external dependency (no ACME, no DNS, no certificate authority account)
- The certificate is presented within the Compose network (not trusted by default — nothing in the network trusts Caddy's local CA; the healthcheck uses `--no-check-certificate` for this reason)
- Browsers will show a certificate warning on first visit — expected and acceptable
  for a local demo
- Switching to a real certificate later requires only replacing `tls internal`
  with a hostname

The alternative of binding to loopback and reaching via SSH tunnel or Tailscale
was considered and rejected: it requires per-developer setup outside the repository
and makes the security property contingent on a step that is easy to skip.

The site address is `localhost:8080` (not a bare `:8080`). This is required:
Caddy needs a hostname as the certificate subject. A bare port gives Caddy no
subject to issue for, so `tls internal` on `:8080` creates a TLS connection
policy with no leaf certificate — every handshake aborts with `tlsv1 alert
internal error`. The hostname `localhost` is the subject the local CA issues for.
(Narrowed by the 2026-08-20 amendment: the address is now
`localhost:8080, 127.0.0.1:8080, [::1]:8080`, so `localhost` is no longer the
only matched host and no longer the only subject the local CA has issued for.
The prohibition on a bare `:8080` is untouched and still fatal.)

The global block also sets `default_sni localhost`. This handles the case where a
connection arrives with **no SNI at all** — for example, a CLI tool that connects
to an IP literal without sending a server name. Without `default_sni`, such
connections would abort the handshake because Caddy has no certificate to present.
With it, Caddy presents the `localhost` certificate and the connection proceeds
(the client may reject the certificate, but the handshake completes).

`default_sni` does **not** help when a client sends a **non-empty** SNI that does
not match `localhost` — for example, `openssl s_client -servername evil.example`
— which still aborts with `tlsv1 alert internal error`.

For the EC2 case specifically: RFC 6066 forbids IP literals in SNI, so a browser
connecting to `https://<public-ip>/` sends **no SNI at all** (empty string).
`default_sni localhost` therefore completes the TLS handshake and presents the
`localhost` certificate. However, the HTTP routing is gated on `host: localhost`,
so the request returns an **empty 200 with `Server: Caddy` and none of the
security headers** — the site is not served. The EC2 deployment is therefore
localhost-only with `tls internal`. Deploying to a public hostname requires
replacing `tls internal` with a real certificate (ACME or otherwise), at which
point `default_sni` can be removed.

(Narrowed by the 2026-08-20 amendment for the **loopback** literals only. HTTP
routing is now gated on `host: localhost`, `127.0.0.1` **or** `[::1]`, so
`https://127.0.0.1/` and `https://[::1]/` are served with the full header set.
The paragraph above still holds verbatim for any *other* host — an arbitrary
public IP or an unmatched `Host` still gets the empty 200. The EC2 conclusion is
unchanged.)

The global directive is `auto_https disable_redirects` (not `auto_https off`).
`auto_https off` disables certificate management entirely, which would prevent
`tls internal` from provisioning a leaf certificate. `disable_redirects` only
suppresses the automatic HTTP→HTTPS redirect, which is correct here because
nothing listens on port 80 after the port mapping change below.

### Port mapping

Caddy continues to listen on port 8080 inside the container (non-root cannot bind
below 1024). The host port mapping in `compose.app.yml` changes from `80:8080` to
`443:8080`, so the application is reached at `https://localhost/` rather than
`http://localhost/`.

### Security response headers

A `header` block in the Caddyfile applies the following headers to all responses.
A matching `handle_errors` block re-applies the same set to Caddy-generated error
responses (502, 404, 413, etc.), which the main `header` block does not cover.

| Header | Value | Rationale |
|---|---|---|
| `Content-Security-Policy` | `default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'` | Restricts resource loading to same-origin. Prevents plugin injection, base-tag hijacking, and clickjacking. `form-action 'none'` prevents injected forms from exfiltrating data — this directive has no `default-src` fallback and must be stated explicitly. |
| `X-Content-Type-Options` | `nosniff` | Prevents MIME-type sniffing. A browser that sniffs a response as a different type than declared can execute content the server never intended to be executable. |
| `Referrer-Policy` | `no-referrer` | Prevents the token-bearing URL from leaking in the `Referer` header to any third-party resource. |
| `Strict-Transport-Security` | `max-age=63072000; includeSubDomains` | Tells browsers to use HTTPS for this origin for the next two years. Safe to set because TLS is now live. See HSTS blast-radius note in Consequences. |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=()` | Disables browser features this application does not use. Reduces the attack surface if a future dependency tries to use them. |
| `Cross-Origin-Opener-Policy` | `same-origin` | Added 2026-08-20 (EOP-107). Severs the `window.opener` reference when another origin opens this document, so an opener cannot reach into this page's window object. Nothing here opens a cross-origin window, so this closes an inherited default rather than a live defect. |
| `Cross-Origin-Resource-Policy` | `same-origin` | Added 2026-08-20 (EOP-107). Instructs browsers to refuse to embed this origin's responses from another origin, blocking cross-origin read attacks such as Spectre-style side channels. |
| `Cross-Origin-Embedder-Policy` | `require-corp` | Added 2026-08-20 (EOP-107). Refuses to load any cross-origin subresource that has not opted in. Safe here precisely because there are none: the GOV.UK CSS and fonts are copied into the bundle and served from `/assets`, and the only external URL in the front end is a footer navigation link, which is not a subresource. |
| `-Server` | (removed) | Removes the `Server: Caddy` header to avoid disclosing the web server product on `host: localhost` responses. Caddy sends no version string, but the product name itself is useful to an attacker scanning for known vulnerabilities. Note: on the unmatched-host path the header is not removed — see the EC2 limitation in Consequences, as narrowed by the 2026-08-20 amendment, which brings the loopback IP literals onto the matched path. |

The `Strict-Transport-Security` header is included because TLS is confirmed live
in this same change. Setting HSTS on a plain-HTTP site would lock browsers out;
that risk does not apply here.

### Token-storage constraint

ADR-015 decided that the token must be held in `sessionStorage` (per-tab, survives
refresh, does not survive tab close). That decision is unchanged and is not
superseded by this ADR.

This ADR adds one constraint that was implicit in ADR-015 but not stated
explicitly:

**The token must never be stored in `localStorage`.**

The reason is that `localStorage` is readable by any script on the same origin,
including injected scripts. A non-expiring credential in `localStorage` is
permanently accessible to any XSS payload, regardless of the CSP above (which
mitigates injection but does not eliminate it). `sessionStorage` is also readable
by injected scripts, but its per-tab scope limits the blast radius: closing the
tab ends the exposure. That is the trade ADR-015 already accepted.

The practical consequence for EOP-11 (the lobby UI): the token must be held in
`sessionStorage` as ADR-015 decided, and must never be written to `localStorage`.

## Consequences

**Positive:** The bearer token no longer travels in plaintext. Any future exposure
beyond one machine — a tunnel, a port forward, a cloud deployment — does not
immediately make the token capturable.

**Positive:** Standard security response headers are in place before the lobby UI
(EOP-11) is built. The CSP is set at the proxy layer, so it applies regardless of
which framework or library serves the front end.

**Positive:** HSTS is set, so browsers that have visited once will enforce HTTPS
for two years even if the URL is typed as `http://`.

**Positive:** `default_sni localhost` ensures that connections arriving with **no
SNI at all** — for example, a CLI tool connecting to an IP literal — fall back to
the `localhost` certificate rather than aborting the handshake. This is a
convenience for local tooling only.

**Negative:** The demo is localhost-only with `tls internal`. RFC 6066 forbids IP
literals in SNI, so a browser connecting to the EC2 public IP sends **no SNI at
all** (empty string). `default_sni localhost` completes the TLS handshake and
presents the `localhost` certificate, but the HTTP routing is gated on
`host: localhost`, so the request returns an **empty 200 with `Server: Caddy`
and none of the security headers** — the site is not served. A non-empty
mismatched SNI (e.g. `openssl s_client -servername evil.example`) still aborts
with `tlsv1 alert internal error`. Deploying to a public hostname requires
replacing `tls internal` with a real certificate (ACME or otherwise).
Narrowed by the 2026-08-20 amendment: the loopback IP literals are now matched
hosts and are served with the full header set. Every other unmatched `Host`,
including a public IP, still behaves exactly as described above.

**Negative:** Browsers will show a certificate warning on first visit to
`https://localhost/`. This is expected and acceptable for a local demo. A real
certificate eliminates the warning but requires a domain name. Note that HSTS
makes this warning non-bypassable: once a browser has seen the HSTS header it
must refuse the click-through override. Developers must trust the Caddy local CA
in their trust store to avoid a permanently unreachable host. The Caddy data
directory is persisted in a named Docker volume (`eop_caddy_data`) so that the
CA survives container recreates — trusting the root once is sufficient for the
lifetime of the stack.

**Negative:** HSTS on `localhost` is port-agnostic. Once a browser has seen the
`Strict-Transport-Security` header for `localhost`, it will refuse plain HTTP on
**all** `localhost` ports for two years — including `http://localhost:8080`
(the `./mvnw spring-boot:run` path), `http://localhost:3000` (Grafana),
`http://localhost:8086` (InfluxDB), and the Vite dev server. Recovery requires
manually clearing `chrome://net-internals/#hsts` or the browser equivalent.
This is an accepted cost for a local demo.

**Negative:** The port mapping changes from `80:8080` to `443:8080`. Any
documentation, script or bookmark that references `http://localhost/` must be
updated to `https://localhost/`.

**Negative:** The `tls internal` certificate is not trusted by the Java HTTP
client used in integration tests. Integration tests run against the Spring Boot
application directly (not through Caddy), so this is not a problem for the test
suite. Any future test that exercises the full stack through Caddy would need to
either trust the Caddy CA or disable certificate verification.

**Neutral:** The `Content-Security-Policy` header is set at the proxy layer. The
React front end (EOP-11) must not rely on inline scripts or styles, because the
CSP's `default-src 'self'` would block them. This is the correct constraint for a
React application built with Vite, which emits content-hashed external files.

## Amendments

**2026-08-20 — cross-origin isolation headers added; the IP-literal bypass is
closed for the loopback addresses and narrowed, not eliminated; source maps are
refused at the edge (EOP-107).**

Three headers join the list above — `Cross-Origin-Opener-Policy: same-origin`,
`Cross-Origin-Resource-Policy: same-origin` and `Cross-Origin-Embedder-Policy:
require-corp` — set in both the site `header` block and the `handle_errors`
header block. The two lists must stay header-for-header identical: an error
response served under a weaker policy than a success response is a gap, and the
duplication is the price Caddy charges for closing it.

None of the three fixes a live defect. There is no cross-origin subresource in
the front end to isolate — the GOV.UK CSS and fonts are copied into the bundle
by `ui/scripts/copy-govuk-assets.mjs` and served from `/assets`, and the only
external URL anywhere in `ui/` is a footer link to the Creative Commons licence,
which is a navigation target and not a subresource. That absence is exactly what
makes `require-corp` safe to assert rather than a change that has to be tested
against a working page: there is nothing for it to refuse.

**The IP-literal bypass this ADR documented is now closed for every address a
local browser can use, and no further.** The site address is
`localhost:8080, 127.0.0.1:8080, [::1]:8080`, so a visit to `https://127.0.0.1/`
matches a site block and receives the full header set. Verified against the
running stack rather than read off the config: before the change that URL
returned an empty `200` with `Server: Caddy` and none of the headers; after it,
`curl -sk -D- https://127.0.0.1/` returns `200` with all eight security headers
and no `Server` header.

Be precise about *why* that works, because the obvious explanation is the wrong
one. Caddy's internal CA did mint leaves for the new subjects — `ls
/data/caddy/certificates/local/` in the container shows `localhost`, `127.0.0.1`
and `--1` — but none of the IP leaves is ever *presented*. RFC 6066 forbids an IP
literal in SNI, so a request to `https://127.0.0.1/` still arrives with no SNI at
all, still falls to `default_sni localhost`, and is still answered with the
`localhost` leaf; a browser there still gets a name-mismatch warning, and `curl
-k` is what hides it. What actually closed the defect is **HTTP `Host` routing**:
Caddy matches a request to a site block on the `Host` header, not on SNI, and
`127.0.0.1` and `[::1]` are now in that list. Certificate issuance was neither
the problem nor the fix. Nobody should read this amendment and expect the TLS
warning to have gone away.

What is *not* closed: an arbitrary unmatched `Host` still takes the default path
and still gets an empty `200` with `Server: Caddy`. Naming every possible host is
not a strategy, and the two ways out are both out of proportion to a
localhost-only demo — a real certificate for a real name, or on-demand internal
TLS, which Caddy will only enable behind an `ask` endpoint that has to authorise
each name. The Decision's ruling on the site address stands unchanged and should
not be re-litigated: a bare `:8080` gives the internal issuer no subject, and
every handshake aborts with `tlsv1 alert internal error`. That has been tried.

**Two hardening options were considered and declined, with reasons, so that
declining them is a decision on the record rather than an omission.** Trusted
Types (`require-trusted-types-for 'script'`) needs a policy created in
application code, and its failure mode is a runtime refusal visible only in a
real browser — nothing in this pipeline runs one, so enforcing it blind would
ship an untested failure mode in exchange for a defence against DOM sinks the
code does not use. A nonce-based CSP is not available to this topology at all: a
static `file_server` cannot rewrite `index.html` per request to inject a fresh
nonce, and Vite emits only external content-hashed files, so `default-src 'self'`
already refuses every inline script. Revisit the first if the front end ever
assigns to a DOM sink, and the second if `index.html` ever becomes templated.

**Source maps are refused at the edge.** `ui/vite.config.ts` sets
`sourcemap: false`, so the production build emits none — verified in the image
itself (`find /srv -name '*.map'` returns nothing). A `@sourcemap path *.map`
matcher in `ui/Caddyfile` nevertheless answers `error 404`, and it is not
redundant: the SPA `try_files` fallback would otherwise serve `index.html` with a
`200` for a `.map` request, which turns a regression into something that looks
like it works. `error` rather than `respond` so `handle_errors` re-applies the
security headers and the response is byte-identical to any other 404 — verified:
`https://localhost/assets/index-abc.js.map` returns `404` with all eight headers.

One consequence of that matcher is not obvious from reading the file, and both
review gates found it independently, so it belongs on the record: Caddy's
Caddyfile adapter orders sibling `handle` blocks by matcher *specificity*, not by
source position, so `*.map` outranks `handle /api/*` even though it is written
after it. `GET /api/v1/sessions/x.map` is therefore refused at the edge with
Caddy's own `404` instead of reaching the application. It fails closed, nothing
can currently be shadowed (every path parameter in this API is a UUID), and a
blanket refusal is the safer default — so the matcher was left broad and the
comment in `ui/Caddyfile` corrected instead. Narrow it to `path /assets/*.map` if
an API route ever legitimately needs that suffix.

## Related

- [ADR-015](ADR-015-player-identity.md) — the token this TLS protects; its
  `localStorage` prohibition is formalised here; the `sessionStorage` decision
  is unchanged
- [ADR-017](ADR-017-frontend-delivery-topology.md) — Caddy as the single entry
  point; this ADR adds TLS to that topology and changes the host port from 80 to 443
- [ADR-012](ADR-012-deployment-target.md) — deployment target; the local-only
  premise that made plaintext acceptable is weakened by any future exposure;
  the stale "token still travels in plaintext" claim in ADR-012 is closed by this ADR
- [ADR-016](ADR-016-local-container-runtime.md) — where the application runs
- [ADR-033](ADR-033-session-creation-rate-limit-and-body-size-cap.md) — the prior
  Caddyfile-modifying ADR (body-size cap and session creation rate limit)
- EOP-21 — the story that implements this decision
- EOP-11 — the lobby UI that must honour the token-storage constraint
