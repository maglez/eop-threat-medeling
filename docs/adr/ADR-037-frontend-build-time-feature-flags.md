# ADR-037: Front-End Feature Flags Are Build-Time Vite Variables, Not Runtime Properties

**Status:** Accepted (EOP-11, 2026-08-16)

**Date:** 2026-08-16

**Deciders:** Miguel González

## Context

EOP-11 delivers the first front-end feature that can be shipped incomplete: the game
lobby (create a session, join by code, watch the roster fill, start play). Following
`.opencode/rules/feature-flags.md`, it merges to `main` switched off. That immediately
raises a question no existing ADR answers: **what is a feature flag on the front end?**

ADR-013 decided the flag mechanism for this project, and it decided it for exactly one
runtime. Its title is *Feature Flags via Spring Configuration Properties* and its
decision is "Feature flags are Spring configuration properties", read by
`@ConditionalOnProperty` so that a disabled feature's **bean does not exist**. That
mechanism is unavailable in a browser. There is no Spring context, no bean graph to
omit from, and no server round-trip at render time — the React bundle is static assets
served by Caddy's `file_server` (ADR-017), so by the time the flag matters the server
is not in the conversation at all.

Three options were considered.

**Serve the flag from the API.** Add the flag to a `GET /api/v1/config` payload and have
the SPA fetch it at boot. This keeps one source of truth in `application.yml` and makes
the flag flippable without a rebuild. It costs a blocking request before first render,
introduces a new public endpoint that advertises which features exist, and makes the
SPA's initial paint depend on the backend being reachable. For a flag whose whole
purpose is to hide unfinished work in a locally-deployed demo application, that is a
large amount of machinery.

**A runtime-injected global.** Have Caddy or an entrypoint script template a
`window.__EOP_FLAGS__` object into `index.html`. Flippable without rebuilding the
bundle, but it puts application configuration inside the web server's templating, splits
the flag's definition across two technologies, and defeats the type checker — a global
patched onto `window` is not something `tsc` can verify a component reads correctly.

**A build-time Vite variable.** `import.meta.env.VITE_LOBBY_UI_ENABLED`, substituted
textually by Vite when the bundle is built.

## Decision

**Front-end feature flags are build-time Vite environment variables, declared in
`ui/src/vite-env.d.ts` and read through `import.meta.env`.** They are a *different
mechanism* from ADR-013's flags, not an extension of them, and the two namespaces do
not correspond.

Four rules follow, and they are the substance of this decision.

**The prefix is `VITE_`, and that prefix is a disclosure boundary.** Vite only exposes
variables beginning `VITE_` to client code; everything else in the build environment
stays out of the bundle. This is a security property, not a naming convention: the flag
namespace is *world-readable*. Anyone who can load the page can read the flag's value
out of the JavaScript, because the value is not looked up at runtime — it is
**substituted into the source text at build time**. `VITE_LOBBY_UI_ENABLED === 'true'`
compiles to `'false' === 'true'` in an off build. Therefore **a front-end flag may never
gate anything confidential**, and it is not a security control. It hides an unfinished
affordance from a user; it does not withhold a capability from an attacker. The
capability is withheld by the server, or it is not withheld.

**The comparison is `=== 'true'`, against a `string`.** `vite-env.d.ts` types
`VITE_LOBBY_UI_ENABLED` as `string`, not `boolean`, because that is what textual
substitution produces. An unset variable is `undefined`, and `undefined === 'true'` is
`false`, so **an undeclared flag reads as off**. This satisfies the fail-closed default
that `feature-flags.md` requires, and it is why the strict comparison is mandatory:
truthiness would make the string `"false"` enable the feature.

**Flipping a front-end flag is a rebuild, not a restart.** ADR-013's flags are bound at
startup, so flipping one restarts the application. A `VITE_` flag is baked into the
compiled asset, so flipping one means `npm run build` and redeploying the bundle. This
is strictly less convenient and is accepted deliberately: the flag exists to keep
unfinished work dark between merge and completion, a period measured in stories, not to
be toggled operationally.

**The flag must be evaluated where the feature is entered, not only where it is
advertised.** A front-end flag has no bean to omit, so it has no equivalent of
ADR-013's structural guarantee that a disabled feature is absent. Disabling a button is
an *affordance* change; it is not a gate, because a route, a rehydrated state or a
hand-edited `sessionStorage` value can reach the feature without passing through that
button. Every path that admits a user to a flagged feature must therefore test the flag
independently — this is `security.md`'s defence-in-depth rule applied to a mechanism
that cannot enforce it structurally.

### What this ADR does not decide

Two other EOP-11 choices look like new decisions and are not; they are **applications of
ADR-015**, recorded here only so that a reader looking for them is sent to the right
place rather than finding a competing answer.

*Token custody in `sessionStorage`* was decided by ADR-015 ("`sessionStorage`, not a
cookie and not `localStorage`") and reaffirmed by its 2026-08-15 amendment. EOP-11
implements that decision under the key `eop_session`; it does not re-take it. The
reconnect-after-refresh behaviour the lobby exhibits is the *consequence* ADR-015
predicted, not a new pattern.

*`fetch`-based SSE reading rather than `EventSource`* was likewise anticipated by
ADR-015, which recorded it as an open implementation question with a constraint on the
answer: the token "must not" become a query parameter, because it would land in access
logs. EOP-11 answers it with `fetch` + `ReadableStream`, preserving the custom header.
That closes ADR-015's open question, and the closure is recorded as an amendment there
rather than as a decision here.

## Consequences

**ADR-013's title is now literally accurate and its scope is narrower than it reads.**
Anyone citing "the feature flag ADR" must now say which runtime they mean. `eop.features.*`
governs Spring beans; `VITE_*` governs compiled front-end branches. A single logical
feature spanning both tiers needs **two** flags with two lifecycles, and they can
disagree — a front-end built with the flag on, talking to a backend with
`eop.features.session-lifecycle` off, is a reachable and untested combination.

**There is no flag catalogue and no audit trail, exactly as ADR-013 accepted.** The
front-end position is whatever the environment of the last `npm run build` contained.
Unlike the backend, where `application.yml` records each flag next to its default, a
`VITE_` flag's default lives only in `vite-env.d.ts` as a *type* — which declares the
variable's existence but not its value. Nothing in the repository states what the flag
was set to for a given built artefact.

**Fail-closed costs a test.** Because the default is off and the test environment sets
no `VITE_` variables, the suite exercises the **off** position by default and the on
position only if a test explicitly stubs it (`vi.stubEnv`). A flagged front-end feature
whose tests never stub the flag on is a feature whose tests never run it, while
appearing green. `feature-flags.md`'s requirement to test both positions is therefore
sharper here than on the backend, where the suite defaults flags on.

**The gating rule above is a review obligation, not a compiler one.** Nothing in
TypeScript can detect that a component reachable by state rehydration failed to consult
the flag. EOP-11 meets this obligation at both entry points: `HomeView` disables the
Create and Join buttons when the flag is off, and the `sessionStorage` rehydration
branch in `App.tsx` evaluates `isLobbyUiEnabled` *before* reading `sessionStorage` —
returning `{ screen: 'home' }` immediately if the flag is off, so a stored session
cannot bypass it. Both positions are tested: `App.test.tsx` stubs
`VITE_LOBBY_UI_ENABLED` to `'true'` for the enabled path and asserts that a valid
stored session is ignored when the flag is off.

**`VITE_LOBBY_UI_ENABLED` retired — `VITE_GAME_SCREEN_ENABLED` is now the live instance of this pattern (EOP-77 amendment, 2026-08-18).** The lobby UI feature was confirmed stable and the flag was removed per `feature-flags.md`. `HomeView`'s Create and Join buttons are now unconditionally enabled; the `isLobbyUiEnabled` guard and its `sessionStorage` short-circuit have been deleted from `App.tsx`. The flag-specific tests in `App.test.tsx` have been removed. `VITE_GAME_SCREEN_ENABLED` (declared in `ui/src/vite-env.d.ts`, read in `LobbyScreen`) is now the sole active front-end flag and the concrete example of the gating rule above: it gates the transition from lobby to `GameScreen`, and both positions (flag on and flag off) are tested via `vi.stubEnv` in `App.test.tsx`.

**`import.meta.glob` cannot be dead-code-eliminated by a flag (EOP-66/EOP-74 amendment).**
Vite resolves `import.meta.glob` at parse time (module graph construction), before any
tree-shaking or flag substitution occurs. A ternary guard such as
`import.meta.env.VITE_MY_FLAG === 'true' ? import.meta.glob(...) : {}` does **not**
prevent the matched assets from being emitted to `dist/` — Vite processes the glob
pattern unconditionally and includes all matched files in the bundle regardless of the
runtime value of the flag. The flag controls whether the application *renders* those
assets, not whether they are *present* in the bundle. Consequence: a feature that gates
heavy static assets (images, fonts, large JSON) behind a `VITE_` flag will ship those
assets in every build, including flag-off builds. This is acceptable for public CC-BY
artwork (EOP-66: 68 card PNGs, ~6.7 MB) but would be inappropriate for unreleased
artwork, confidential data, or any asset whose presence in the bundle is itself a
disclosure. When asset emission must be conditional, the assets must be served from a
separate origin or fetched lazily at runtime rather than bundled via `import.meta.glob`.

## Related

- [ADR-013](ADR-013-feature-flags.md) — the backend flag mechanism this one is deliberately *not* an extension of; `@ConditionalOnProperty` removes a bean, which has no browser equivalent
- [ADR-015](ADR-015-player-identity.md) — decides `sessionStorage` custody and poses the `EventSource`-versus-`fetch` question that EOP-11 closes; not re-decided here
- [ADR-014](ADR-014-realtime-transport.md) — chooses SSE as the transport whose client this flag gates
- [ADR-017](ADR-017-frontend-delivery-topology.md) — why the bundle is static assets behind one origin, which is what makes a build-time flag possible at all
- [ADR-009](ADR-009-frontend-react-typescript.md) — the React + TypeScript + Vite stack that supplies `import.meta.env`
- `.opencode/rules/feature-flags.md` — the fail-closed default and both-positions testing rule this decision inherits
- `.opencode/rules/security.md` — the defence-in-depth rule behind the "gate at entry, not at the button" clause
- EOP-11 (the lobby this flag gates)
