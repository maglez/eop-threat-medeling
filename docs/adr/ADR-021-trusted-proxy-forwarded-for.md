# ADR-021: Trust `X-Forwarded-For` Only From An Explicit Allow-List Of Proxies

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** @tech-lead, @security-auditor

## Context

[ADR-017](ADR-017-frontend-delivery-topology.md) established that Caddy is the only published
entry point. The browser and the API share one origin, `/api/*` and `/health` are reverse-proxied
to `app:8080` over the Compose network, and the application container publishes no host port at
all. From that topology the code drew a conclusion: if the only thing that can reach the
application is the proxy, then the forwarding header the proxy writes can be believed. Caddy
*appends* to `X-Forwarded-For`, so the last comma-separated entry is the one Caddy itself wrote,
and reading the last entry rather than the first defeats a caller who pre-seeds a fake chain.

That reasoning was sound and still is. It was also incomplete, because it is valid **only if the
peer really is Caddy**, and nothing verified that. The topology was treated as an invariant when it
is a deployment arrangement. Three ways for the assumption to fail already existed on the day it
was written: a developer running `./mvnw spring-boot:run` reaches port 8080 directly; any process
inside the container network — the PostgreSQL container, an exec'd shell, a future sidecar — can
open a socket to `app:8080` without passing through the proxy; and any later change that publishes
the application port, temporarily for debugging or permanently by accident, exposes it to the host.

In each of those cases the caller supplies, verbatim, the exact string the join-attempt limiter
keys its buckets on. Rotating the header once per request yields a fresh, empty bucket every
time, so `MAX_FAILURES_PER_ADDRESS = 10` never fires. The per-code counter cannot compensate: an
attacker guessing codes uses a different code on every attempt, so the per-code window never
accumulates either, and beyond `MAX_TRACKED_KEYS = 10_000` the code map stops recording at all.
The limiter still ran, still allocated, still returned 429 in its unit tests — and enforced
nothing against a caller who bothered to set one header.

That matters more here than the same defect would matter in most applications, because
[ADR-019](ADR-019-session-lifecycle-and-join-codes.md) designates this limiter a **primary
security control**. Its wording is deliberate and worth quoting rather than paraphrasing: "at
thirty bits the rate limiter is a primary security control, not defence in depth. Thirty bits is
unguessable only while guessing is slow." A six-character Crockford base32 join code is the only
thing standing between a stranger and someone else's game session, and thirty bits of entropy was
accepted precisely on the strength of the throttle. So this defect did not thin out a
defence-in-depth layer that other layers could cover for. It removed the control the entropy
budget in ADR-019 was calculated against.

One further piece of evidence is recorded here because it is the most persuasive artefact the
story produced. The existing integration suite used a helper called `freshAddress()` to give each
test its own throttle bucket, so that tests could run in any order without one test's failed joins
poisoning the next. It worked only because the vulnerability existed — the helper was a test
setting its own rate-limiter key from outside the process, which is exactly the attack. Closing
the hole broke sixteen previously-passing tests. The suite had been silently depending on the bug
for its isolation, and a green build had therefore been evidence that the bug was still present.

## Decision

### Trust is an explicit, default-denied allow-list, and the default is empty

A new configuration property, `eop.web.trusted-proxies`, holds a list of literal IP addresses or
canonical CIDR blocks. `X-Forwarded-For` is read **only** when the peer address — `getRemoteAddr()`,
the one value in the request that a remote caller cannot choose — is on that list. Otherwise the
header is ignored entirely and the peer address is used as the client address.

The default is the empty list. An application deployed by someone who never thought about
forwarding headers trusts nobody, reads no headers, and keys its throttle on the peer. That is the
safe failure: it is wrong in the direction of over-counting a shared proxy rather than of
accepting whatever a caller asserts. This follows `.opencode/rules/security.md` directly on three
of its six points — explicit allow-lists rather than pattern matching, fail securely with
default-denied access, and defence in depth by re-deriving trust at the point of use instead of
inheriting it from the network diagram.

The mechanism is three small classes in `adapter/web`: `TrustedProxyProperties` binds and copies
the list, `TrustedProxies` validates it into ranges and answers `includes(peer)`, and
`ClientAddressResolver` is the single place that decides what the client address is. The old
static `ClientAddresses` utility is deleted, so there is no second answer to the same question
left in the codebase.

### The value that is trusted is canonicalised before it is used as a key

Every address that leaves the resolver has passed through `IpLiterals.canonical`. `10.0.0.1` and
`::ffff:10.0.0.1` become one string, and a dotted quad is parsed by hand under strict rules —
exactly four parts, one to three digits each, no leading zero — rather than handed to the platform.

### A malformed allow-list entry fails startup

`TrustedProxies.of` throws `IllegalArgumentException` naming the offending entry and the reason,
which fails the bean, which fails startup. Non-canonical CIDR — a block with host bits set, such as
`10.0.0.5/8` — is rejected rather than silently masked.

### Caddy now writes the header explicitly

Both `handle /api/*` and `handle /health` in `ui/Caddyfile` carry
`header_up X-Forwarded-For {remote_host}`, overwriting whatever chain arrived with the immediate
peer.

## Consequences

**Positive:** the join-attempt limiter enforces what ADR-019 says it enforces. The rotation attack
is covered by an integration test that drives ten failing joins, each with a distinct rotated
`X-Forwarded-For` *and* a distinct join code so that only the per-address counter can be
responsible, and asserts the eleventh is 429. Its sibling test configures
`eop.web.trusted-proxies=127.0.0.1/32` and asserts the opposite outcome from the same traffic —
ten independent buckets and no 429 — which is what makes the first test evidence about the
allow-list rather than evidence that the header is merely unread.

**Neutral — a hand-rolled check was chosen over `server.forward-headers-strategy=FRAMEWORK`.** The
Spring and Tomcat mechanism does this job, is better tested than anything written here, and would
have been fewer lines. It was rejected for two honest reasons rather than one good one. First,
locality: the trust decision belongs in one readable class sitting next to the limiter whose
correctness depends on it, not in a property whose effect materialises inside a servlet-container
valve two layers away, where the next person to read `InMemoryJoinAttemptLimiter` will not find it.
Second, and decisively: the value resolved here is not merely logged or returned by
`request.getRemoteAddr()` — it becomes a key in a rate-limiter map, and keys need canonicalising.
`RemoteIpValve` gives back whatever spelling the header contained. That is harmless when the value
is only reported and unsafe when two spellings of one address mean two buckets. Having to
canonicalise anyway removed most of the saving the framework option promised.

**Neutral — `server.forward-headers-strategy` is pinned to `none`, and that is an accepted design
position rather than a limitation.** Choosing a hand-rolled check is not sufficient on its own: the
framework mechanism had to be actively turned off. Were it enabled, Tomcat's `RemoteIpValve` or
Spring's `ForwardedHeaderFilter` would rewrite `getRemoteAddr()` from the very headers this ADR
refuses to believe — and `getRemoteAddr()` is the single input `ClientAddressResolver` trusts in
order to decide whether to believe anything else. The header would be laundered into the peer
address before the allow-list ever saw it, behind the resolver's back. `none` is already the
effective default, so pinning it changes no behaviour today; the point of pinning is that the
property is now written down, so a future well-intentioned "we are behind a proxy now" change has to
argue with a comment instead of filling in a blank. Verified in the tree rather than assumed:
`src/main/resources/application.yml` line 10 and `src/main/resources/application-prod.yml` line 36
both read `forward-headers-strategy: none`.

The reason this matters is sharper than general caution. `RemoteIpValve`'s default `internalProxies`
includes `172.16.0.0/12` — **which contains this project's own `172.28.0.0/24` Compose subnet** —
and `127.0.0.0/8`, which covers the local `./mvnw spring-boot:run` case. The container's
out-of-the-box trust set is therefore precisely the two environments in which this defect was
originally reachable. Switching the framework strategy on without re-deriving that list from scratch
would re-open EOP-26 in exactly the places EOP-26 was found.

**Neutral — canonicalisation is part of the security property, not tidiness.** `10.0.0.1` and
`::ffff:10.0.0.1` are the same client, and without folding them together they would be two
buckets, halving the effective throttle for a caller who alternates. That is the *same class of
bug* this ADR closes, arriving by a different route. The strict dotted-quad parser exists for the
same reason: the platform has historically accepted octal and abbreviated forms, so `010.1.1.1`
and `8.1.1.1` name one host but would spell two buckets, and `10.1` names a third spelling of a
fourth. Anything that is not a plain, unambiguous literal is rejected rather than guessed at.

**Neutral — address parsing is DNS-free, and it is an explicit character allow-list that makes it
so.** An allow-list whose meaning depends on what a resolver says today is not an allow-list.
Handing a configuration entry to `InetAddress.getByName` would turn a typo into a network call
during startup and a hostile `X-Forwarded-For` value into a network call on the request path, with
the answer decided by whoever controls the zone. IPv6 is the one shape that still delegates to the
platform, and the guard placed in front of that delegation is the whole of the property.

An earlier revision of this ADR claimed the delegation was safe *by construction*, on the grounds
that a colon cannot appear in a hostname and so the JDK would reject rather than resolve. **That
reasoning is false, and it was measured to be false.** `InetAddress.getByName` decides whether to
attempt literal parsing from the *first character alone*, so a colon anywhere else protects
nothing: colon-bearing hostnames go straight to the system resolver. Measured on Java 21.0.12 with
unique, uncacheable names, the original code blocked a request thread for **5020 ms** on a single
cold lookup of `zzz<unique>:80`, 2529 ms for `host<unique>.example.com:80`, and 234 ms per call
averaged over ten. The 169–251 ms first reported when the finding was raised had been measured
against partly-warmed OS negative-cache entries, so the real exposure was an order of magnitude
worse than the number the finding was argued on.

The fix is not the character alphabet first proposed either. `[0-9a-fA-F:.\[\]%]` was measured to
still admit `.a:b`, which still reaches `getaddrinfo`, because a dot is in that alphabet and the
JDK inspects only the first character. What `IpLiterals.isAddressText` implements instead, after
stripping surrounding brackets and splitting at the first `%`, is three independent conditions on
the address part: it must contain `:`; its first character must be in `[0-9a-fA-F:]`, which is the
same test the JDK itself uses to choose literal parsing over resolution; and every one of its
characters must be in `[0-9a-fA-F:.]`. A scope part, where present, must be non-empty with every
character in `[0-9a-zA-Z.\-_]`, which **permits** named interfaces such as `%lo0`, `%eth0.100` and
`%br-lan` rather than guaranteeing they resolve. The distinction matters and was measured: the
character guard is the only thing this repository controls, and passing it merely gets the value as
far as the platform, which then requires the named interface to exist *on the local machine*.
`%eth0.100` and `%br-lan` were both **rejected** on the machine this was measured on, because no
such interface is present there; `%lo0` was accepted there because that machine both has an
interface of that name *and* that interface carries an IPv6 link-local address. Both conditions are
load-bearing and the spelling alone is not the reason, which is the point the next paragraph
develops. The same value is rejected on a Linux host for two independent reasons: the loopback is
spelled `lo` there, and that interface carries `::1/128` alone.
Measured directly, so this is checkable rather than asserted — `java.net.InetAddress.getByName` on
the macOS machine used here returns for `fe80::1%lo0`, `fe80::1%1` and `fe80::1%0`, and raises
`UnknownHostException: no such interface` for `fe80::1%lo`, `fe80::1%eth0` and `fe80::1%br-lan`. Note
that `%lo0` and `%lo` are *inverted* between macOS and Linux, so a named scope is never a
platform-independent example; only a numeric scope such as `%0` or `%1` is.

The interface existing is **necessary but not sufficient**, and this is the part that keeps being
missed. The platform raises a *second*, distinct error — `UnknownHostException: no scope_id found` —
when the named interface exists but carries no IPv6 address of the same local-address type as the
address being parsed, which for `fe80::/10` means it must carry a link-local address of its own.
Measured on the same macOS machine, `java.net.NetworkInterface.getByName` returns non-null for
`en0` and `utun0`, yet `IpLiterals.parse("fe80::1%en0")` and `IpLiterals.parse("fe80::1%utun0")` are
both empty, because neither interface has an `fe80::` address; of nine interfaces present, two fail
this way. `%lo0` succeeds here only because macOS assigns `fe80::1%lo0` to loopback
(`ifconfig lo0` shows `inet6 fe80::1%lo0 prefixlen 64 scopeid 0x1`), which Linux does **not** do for
`lo` — `lo` there carries `::1/128` alone. So "pick any interface that exists" is not a
platform-independent way to obtain a working named scope either; the interface must be one that
carries a link-local address, and on a host with IPv6 disabled no such interface exists at all.
Both failure modes are interface-table failures raised without a resolver call, so neither touches
the DNS-free property; the distinction matters only for anyone writing a *positive* named-scope
example or assertion.
So the alphabet admits these names and the platform disposes of them, host by host — which is the
same fact known limitation 1 records from the other side. What the wide scope alphabet cannot do is
reopen the resolver path, because the address part is validated on its own, and that is the only
property claimed for it here.

Post-fix, the same calls raise no resolver error at all, and
the check costs 0.005–0.006 ms on the first call in a fresh process and 0.0006 ms per call in steady
state once it is JIT-compiled — 200 fresh names rejected in 0.12 ms in total, which is the
steady-state figure restated so it can be divided out on the page rather than taken on trust
(0.12 ÷ 200 = 0.0006). The speed-up is a range rather than a single figure, because it depends on
whether the resolver call it replaces would have been cold or already negative-cached:
234 ms ÷ 0.0055 ms ≈ 43 000× at the mildest pairing, and 5020 ms ÷ 0.0006 ms ≈ 8 000 000× at the
harshest.

An earlier revision quoted a bare "130 000×". It is **withdrawn as unverifiable rather than
re-derived**, because no pairing of the figures recorded above produces it. Only the mildest pre-fix
timing, 234 ms, is small enough to come near: divided by the fresh-process guard cost it gives
39 000–47 000× (234 ÷ 0.006 and 234 ÷ 0.005), and divided by the steady-state cost it gives
390 000× (234 ÷ 0.0006). 130 000× falls in the gap between those two bands and matches neither,
and every pairing built on 2529 ms or 5020 ms exceeds 400 000×. Reproducing 130 000× would require
a per-call cost of 0.0018 ms — a figure that appears nowhere in this ADR and was never recorded
beside it. That a reader could not check the number against the measurements next to it is the same
defect as the sentence this paragraph exists to correct, which is why it is struck rather than
replaced with a better-sourced single number.

**Why this was worth correcting even though the path is reachable only from an already-trusted
peer.** In the shipped configuration nothing hostile reaches `parseIpv6`, because the header is
read only from an allow-listed proxy. The cost was never the exposure; it was the sentence. A false
security rationale is precisely the thing that stops the next reviewer re-checking — "DNS-free by
construction" invites a reader to accept the property rather than test it, and it would have gone
on being unexamined on the day someone widened the allow-list to a range shared with a less trusted
peer, or reused `IpLiterals` somewhere its input is not pre-filtered. The guarantee is real, but it
is *ours*: it is held by a handful of lines of validation in this repository, not granted by the
JDK. Documenting it as the JDK's would have transferred responsibility for it to a party that never
accepted it.

`InetAddress.ofLiteral` would have replaced most of `IpLiterals`, but it arrived in Java 22 and
this project targets Java 21 (ADR-002).

**Negative — a malformed entry takes the application down instead of being skipped.** This is the
intended trade and it should be stated as a cost. A typo in `EOP_WEB_TRUSTED_PROXIES` now stops the
container from starting, which is a worse outage than the alternative and a much better failure.
Skipping the bad entry would produce an application that starts happily and trusts *less* than its
configuration claims, with only an unread log line as evidence — the operator believes the proxy
is allow-listed, the limiter is quietly keying on the proxy's own address for every player, and
nothing anywhere reports a problem. Rejecting non-canonical CIDR is the mirror image of the same
argument: quietly masking `10.0.0.5/8` to `10.0.0.0/8` would trust sixteen million hosts where the
author plainly meant one, and it would do so while looking like the configuration that was written.

**Neutral — the deployed value lives in `compose.app.yml`, not `application-prod.yml`.** It is set
as `EOP_WEB_TRUSTED_PROXIES: 172.28.0.10/32` on the `app` service, a few lines from the
`ipv4_address: 172.28.0.10` pinned on the `caddy` service in the same file. One file owns both
halves of one fact, so the two cannot drift apart: moving the proxy's address without updating the
allow-list is a change to a file where the contradiction is visible in a single screen, and a `/32`
keeps the trust to Caddy alone rather than to everything sharing the network. This required pinning
the Compose default network to the fixed subnet `172.28.0.0/24`, because Compose otherwise assigns
addresses dynamically and there would be nothing stable to allow-list at all, and then confining the
dynamic pool with `ip_range` so that no other container can be handed the proxy's static address by
chance (known limitation 6). It is deliberately
*not* in `application-prod.yml`: the value is a container address — deployment topology, not
application configuration — and the precedent is already set in that file by `DATASOURCE_URL`,
which is likewise supplied by Compose rather than baked into the profile.

**Neutral — the Caddyfile change is hardening, not the fix.** The allow-list is the fix; the
application is now correct even if Caddy forwards a chain a caller invented, because it will not
read the header from an untrusted peer in the first place. `header_up X-Forwarded-For {remote_host}`
discards that chain one hop earlier, which is worth having and is not load-bearing. It is safe
under either Caddy directive ordering: applied after Caddy's own append it leaves a single entry,
and applied before it the value is appended to itself. The application reads the last entry, which
is the real client either way. That directive is a set rather than an append, which is what discards
an invented chain and is also why a second proxy placed in front of Caddy would need it revisited —
see known limitation 5.

**Neutral — the test suite's default is production's default.** Deny-all is inherited from the
shipped `application.yml` and nothing in `src/test/resources/application.properties` overrides it,
so every test in the suite runs against the same trust posture as production. Only the one nested
test class that exists to prove the trusted path works sets
`eop.web.trusted-proxies=127.0.0.1/32`. The tempting alternative was to trust `127.0.0.1`
suite-wide, which would have restored the old per-test throttle isolation for free and let the
sixteen broken tests pass unchanged. It was rejected because it would have re-opened the hole
everywhere in the suite *except* in the tests that assert it is closed — the build would then have
been green for the same reason it was green before EOP-26. The sixteen tests were fixed instead:
`freshAddress()` is now `unusedAddressHint()`, documented as having no effect, and the throttling
tests get a fresh limiter from `@DirtiesContext` rather than from a forged header.

## Known limitations

The first three were found by @tester-unit-and-quality during this story and are recorded as they
were reported, with limitation 1 corrected below after its behaviour was actually measured. The four
that follow were added in a later review round, after @security-auditor stated plainly that those
three were **not** the complete set. The eighth is a technical-debt position ruled by
@code-reviewer, recorded here so the next person to open `IpLiterals` finds the decision rather than
rediscovering the smell. None of the eight is softened: each records what is wrong, what
makes it harmless today, and what would make it dangerous.

1. **`IpLiterals.canonical` drops an IPv6 scope id.** Two spellings of one link-local address that
   differ only in scope collapse into a single throttle bucket, because `canonical` re-formats from
   the sixteen address bytes and those bytes carry no scope. The measured Java 21 behaviour is more
   specific than this entry first claimed, and it narrows the case rather than widening it. A
   **named** scope for an interface that is absent locally is rejected outright: `fe80::1%eth0`
   raises `UnknownHostException: no such interface eth0`, so `parse` returns empty and no bucket is
   keyed at all. A **numeric** scope is accepted even when no such interface exists: `fe80::1%99999`
   parses, the platform spelling it back as `fe80:0:0:0:0:0:0:1%99999`. And a name that is present
   locally *and* whose interface carries an IPv6 link-local address is accepted: `%lo0` parses on the
   macOS machine used here, canonicalising to `fe80:0:0:0:0:0:0:1`. Presence alone is not sufficient
   — `%en0` and `%utun0` name interfaces that exist there and are still rejected, with
   `no scope_id found`. So the two-spellings-one-bucket collapse requires either a numeric scope or
   the name of a local interface that carries a link-local address; an arbitrary invented interface
   name cannot reach it. @security-auditor confirms the
   limitation is **genuinely unreachable from the internet**: link-local is not routable, and the
   only path by which such a value arrives is an allow-listed proxy writing a socket address into
   the header. That is an auditor's ruling of unreachability, not our own estimate that the risk
   feels low. It remains the same class of bug as the one this ADR closes — two clients sharing a
   key — arriving from the opposite direction, and it is stated here rather than left in a comment.
2. **`0.0.0.0/0` is accepted silently.** It is a valid CIDR block with its host bits clear and a
   prefix length in range, so it passes every check, and it would trust every possible peer and
   restore the vulnerability in full. This is not a defect in the parser — it is a footgun in the
   property. There is no rule the parser could apply that would reject it without also rejecting
   legitimate wide blocks on principle. The mitigation is review of this property's value, which
   means the value in `compose.app.yml` and any environment override of it are things a reviewer
   has to actually look at.
3. **A non-literal `getRemoteAddr()` is passed through, not replaced by the `unknown` sentinel.**
   When the peer address is not an IP literal, `ClientAddressResolver` returns the stripped string
   rather than `"unknown"`, and the limiter keys on that string. Two names for one host would then
   be two buckets. The path is unreachable behind a real servlet container, which is why it is a
   limitation rather than a bug, but the sentinel exists and this case does not use it.
4. **IPv4-*compatible* IPv6 does not fold to its IPv4 form.** `::10.0.0.1` and `::a00:1` both
   canonicalise to `0:0:0:0:0:0:a00:1` — sixteen bytes — while `10.0.0.1` canonicalises to
   `10.0.0.1` — four bytes. One host, **two buckets**: the same bug class this ADR closes, arriving
   from the other direction. The IPv4-*mapped* form is handled correctly by contrast, and the
   contrast is worth stating because it is easy to assume both behave alike: `::ffff:10.0.0.1` folds
   to `10.0.0.1`, because the platform hands back four bytes for it. That folding carries a genuine
   positive @security-auditor found while checking this — a dual-stack Caddy peer arriving as
   `::ffff:172.28.0.10` still matches the `172.28.0.10/32` IPv4 rule in the allow-list, so the
   deployed configuration does not silently stop trusting the proxy on the day the socket is accepted
   over IPv6. Only the deprecated compatible prefix is unhandled, and it is unreachable in the
   shipped configuration: nothing in the path generates that form, and the header is read only from
   an allow-listed peer.
5. **`header_up` overwrites the chain rather than appending to it.** In Caddy,
   `header_up <name> <value>` without a `+` prefix is a set, not an append. That is correct today and
   deliberate — it is exactly what discards a chain an attacker invented — and @security-auditor
   verified that the last-entry rule the application relies on holds under both directive orderings:
   append-then-set leaves `{remote_host}`, set-then-append leaves `{remote_host}, {remote_host}`, and
   the last entry is the true immediate peer either way. But because it is a set, a legitimate second
   proxy placed in front of Caddy — a CDN, a load balancer, an ingress — would have its forwarded
   chain destroyed at this hop, and the application would then key every request on that outer
   proxy's single address. One shared bucket for every player is precisely the outcome this ADR's own
   "Alternatives considered" section rejects under *Drop the header entirely*: a global denial of
   service against legitimate players, triggered by ordinary mistyping. Reaching that state requires
   a topology change, so this is a limitation of the fix rather than a bug in it — but anyone adding
   a hop in front of Caddy must revisit this directive, appending rather than setting, and re-derive
   which entry of the chain is the one to read.
6. **The Compose static address is not IPAM-reserved.** Docker allocates dynamic addresses without
   pre-reserving the statics declared elsewhere in the file, so nothing inherent stops `postgres` or
   `app` being handed `172.28.0.10` on container recreation before `caddy` asks for it. `caddy` then
   fails to start — and the dangerous half is what happens next, because a surviving `app` container
   keeps trusting whatever now holds `.10`, so the allow-list would name a container that is not the
   proxy. @devops-engineer has addressed this in `compose.app.yml`. What that file actually says,
   quoted rather than paraphrased, is:

   ```yaml
   networks:
     default:
       ipam:
         config:
           - subnet: 172.28.0.0/24
             ip_range: 172.28.0.128/25
   ```

   `ip_range` confines Docker's dynamic pool to `172.28.0.128`–`172.28.0.255`, which does not
   contain `172.28.0.10`. The static address is therefore outside the range any container can be
   assigned by chance. Note what does the work: the two ranges being disjoint, not a reservation.
   Docker still does not reserve `.10` — it simply can no longer hand it out. The comment above that
   block explains only the subnet pin ("so the proxy can have a fixed address for the application to
   allow-list. Without this Compose hands out dynamic IPs") and does not mention the range, so the
   reason the range is there is recorded here.
7. **The DNS-free property is not self-enforcing — and that was two distinct gaps, of which one is
   now closed.** The property holds because `parseIpv6` is the only caller of
   `InetAddress.getByName` in `IpLiterals`, and every path into that call passes `isAddressText`
   first. Neither half was enforced by anything when this entry was first written, and the two
   halves have different remedies, so they are separated here.

   *Gap 7a — no test covered the existing guard. Closed.* @security-auditor found this while
   re-auditing the final tree and it was the sharper of the two problems. The "DNS-free guarantee"
   tests rejected only `localhost` and `example.com`, and **both of those are handled by the IPv4
   path** — neither contains a colon, so `parse` routes them to `parseIpv4`, which rejects them on
   part count without `isAddressText` being consulted at all. No test fed a colon-bearing
   non-literal, so nothing exercised `isLiteralStart`. Deleting that one line would therefore have
   reintroduced a blocking resolver call on a request thread **with a green build**, and a
   value-only assertion could never have caught it: `parseIpv6` catches `UnknownHostException` and
   returns empty, so the return value is identical whether the guard is present or the resolver was
   called and failed. @tester-unit-and-quality has closed this by registering a test-only
   `RecordingInetAddressResolverProvider` through `META-INF/services/java.net.spi.InetAddressResolverProvider`
   and asserting that its lookup counter stays at zero. `IpLiteralsTest.DnsFreeLiteralGuard` runs
   `zzz:80`, `localhost:80`, `host.example.com:80`, `_:_` and `.a:b` through both `parse` and
   `canonical` under the armed spy. `.a:b` is the case that matters: a dot is in the address
   alphabet, so it passes the per-character check and only `isLiteralStart` rejects it. Deleting the
   guard now fails the build rather than merely costing latency.

   *Gap 7b — nothing prevents a **new** unguarded call site. Still open.* The test above pins the
   guard on the paths that exist; it cannot pin paths that do not exist yet. A `getByName` added
   elsewhere in the class, or a second path into `parseIpv6` that skips `isAddressText`, would
   silently reintroduce the blocking resolver primitive whose cost is measured under Consequences
   above, and the only symptom would be latency. An ArchUnit rule forbidding
   `InetAddress.getByName` outside the one guarded method, or a `forbidden-apis` signature ban on it
   across the module, would make the property self-enforcing for call sites nobody has written yet.
   Neither is in place. This half remains an accepted gap with a named remedy, not a claim of
   safety — and it is the half that a passing test suite cannot substitute for.
8. **`IpLiterals` now holds two separable responsibilities, and S5 is a warning rather than a
   suggestion.** @code-reviewer raised the single-responsibility question as a suggestion in the
   first review round and **promoted it to a warning** on re-review, on the grounds that the class
   has grown to carry both a character-classification alphabet and the parsing/canonicalisation
   logic that consumes it. The two are separable: the alphabet (`isLiteralStart`,
   `isAddressCharacter`, `isHexDigit`, `isScopeText`, `isScopeCharacter`, `isAddressText`) answers
   *what may be delegated to the platform* and is the security guard; the parsers (`parse`,
   `canonical`, `parseIpv6`, `parseIpv4`, `parseOctet`, `format`) answer *what the bytes are* and
   are the value logic. Measured rather than asserted, since the promotion turned on size:
   `IpLiterals.java` is **229 lines with 12 methods, of which 10 are private helpers**, plus a
   private constructor and three constants. The reviewer's figure of "13 private methods" counted
   the private constructor and predates the Javadoc correction made in this round; the measured
   figures are twelve methods, ten of them private.

   This is **explicitly not a blocker**, and it is recorded as a decision rather than left as a
   smell for the same reason the DNS-free sentence was corrected: the point of the file is that its
   guard is load-bearing, and a reader who opens it and finds an undocumented cohesion problem may
   reasonably start moving the guard around without knowing what it holds up. The trade taken today
   is that keeping the alphabet in the same file as the only code that calls it makes the guard
   visible at its point of use — the same locality argument that chose a hand-rolled check over
   `forward-headers-strategy=FRAMEWORK` under Consequences — and splitting it now would separate the
   guard from `parseIpv6`, the one method whose safety depends on it, while gap 7b is still open.

   **The trigger condition is explicit: a follow-up ticket is to be filed before the next feature
   touches this file.** Whoever next needs to change `IpLiterals` for a reason of their own should
   extract the classification alphabet — plausibly as `IpLiteralAlphabet` — as a separate, prior
   commit, and should do it together with the ArchUnit or `forbidden-apis` rule named in gap 7b,
   because that rule is what would keep the guard mandatory once it no longer sits in the same file
   as its caller. Splitting without that rule would make gap 7b materially worse, so the two are
   sequenced deliberately and not independently schedulable.

## Alternatives considered

**`server.forward-headers-strategy=FRAMEWORK`.** Covered under Consequences above: rejected for
locality of the trust decision and because the resolved value needs canonicalising for use as a
map key, which `RemoteIpValve` does not do.

**Keep trusting the header unconditionally.** The status quo being fixed. It is only defensible
while the deployment topology in ADR-017 holds exactly, it silently stops being defensible the
moment anyone maps the application port, and the failure is invisible — no error, no log, just a
security control that returns 429 in tests and never in production. The sixteen tests that broke
are the measure of how invisible.

**Drop the header entirely and always use `getRemoteAddr()`.** By far the simplest option, and it
is genuinely secure — a caller cannot influence the peer address. It was rejected because of what
it costs behind Caddy: every request would arrive from the proxy's single container address, so
every player in every session would share one throttle bucket. Ten failed joins by ten different
people, anywhere in the world, would then lock out the eleventh. The throttle would stop being a
brute-force defence and become a global denial of service against legitimate players, triggered by
ordinary mistyping. The allow-list exists precisely so that the header can be believed where it is
true and ignored where it is not, instead of choosing one wrong answer for both cases.

## Related

- [ADR-019](ADR-019-session-lifecycle-and-join-codes.md) — designates the join-attempt limiter a primary security control at thirty bits of entropy; this ADR restores the control that decision depends on
- [ADR-017](ADR-017-frontend-delivery-topology.md) — the single-origin topology whose "only Caddy is reachable" property was over-read as a guarantee that the peer is Caddy
- [ADR-013](ADR-013-feature-flags.md) — configuration properties under `eop.*` with `@ConditionalOnProperty`; `eop.web.trusted-proxies` is infrastructure configuration rather than a feature flag and so sits directly under `eop.`, not `eop.features.`
- [ADR-012](ADR-012-deployment-target.md) — a single application instance behind a single reverse proxy, which is what makes a one-entry allow-list sufficient
- [ADR-002](ADR-002-spring-boot-bootstrap.md) — Java 21, which is why `InetAddress.ofLiteral` is unavailable
- [C4 container diagram](../architecture/C4-Diagrams.md) — `ClientAddressResolver` and the trust boundary at the proxy
- [Runtime view](../architecture/runtime-view.md) — where address resolution sits in the join sequence
- `.opencode/rules/security.md` — explicit allow-lists, fail securely, defence in depth
- EOP-26 (this story), EOP-10 (the session lifecycle and the limiter this protects)
