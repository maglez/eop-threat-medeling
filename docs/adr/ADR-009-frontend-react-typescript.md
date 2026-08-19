# ADR-009: Front-End Technology Stack — React + TypeScript + Vite + GOV.UK Frontend

- **Status:** Accepted (amended four times on 2026-08-19 — five stack and layout claims diverged from the shipped code, the DTO mirror's unenforced manual invariant is now a build gate, codegen was re-evaluated once response parsing landed and again declined, and the last bare-`string` contract enum was closed while `Rank` was explicitly rejected as a mirror; see Amendments)
- **Date:** 2026-07-26
- **Author:** Engineering Team
- **Deciders:** Architecture Guardian, UI Builder, Tech Lead

## Context

The EoP card game requires a web front-end for players to view the game board, draw threat cards, interact with STRIDE categories, and observe privilege escalation flows. The following constraints apply:

- **AI-managed project** — code is written and maintained primarily by AI agents, not human developers
- **GOV.UK Design System** — all UI must follow GOV.UK standards, using `govuk-frontend` CSS classes and component patterns
- **TypeScript preferred** — strong typing reduces agent hallucination risk and catches interface mismatches at build time
- **Interactive card game** — the UI must support animations, drag-and-drop (or click-to-play), and real-time state updates

## Options Considered

| Option | Language | Training Data | GOV.UK Fit | Boilerplate |
|---|---|---|---|---|
| **React + TypeScript** | TSX | Excellent — largest training corpus of any UI framework | CSS classes via `className`, no wrapper needed | Low |
| Vue + TypeScript | TS | Good — less than React, occasional v2/v3 confusion | Template-based, clean HTML pass-through | Low |
| Lit + TypeScript | TS | Moderate — web components, less LLM training data | Best — native web components, HTML passes through cleanly | Lowest |
| Angular + TypeScript | TS | Moderate — complex CLI tooling, more agent error surface | Must wrap govuk components in Angular wrappers | High |
| Plain JavaScript | JS | Excellent — but no structure, agents produce inconsistent patterns | Best — raw HTML, but messy for complex UI | None |

## Decision

Adopt **React + TypeScript + Vite + `govuk-frontend` CSS** as the front-end technology stack.

### Why React over alternatives

| Criterion | Decision Rationale |
|---|---|
| **LLM training data** | React components are the single most-represented UI pattern in LLM training corpora. Agents produce correct, idiomatic React code more consistently than any other framework. |
| **TypeScript guardrails** | `tsc` catches hallucinated API calls, missing props, and type mismatches at build time — critical for AI-generated code quality. |
| **Simplicity** | Props in, JSX out, hooks for state/effects — trivial patterns that agents reproduce reliably. |
| **GOV.UK compatibility** | `govuk-frontend` CSS classes apply directly via `className`. No wrapper library needed. The GOV.UK page template can be replicated as a React layout component. |
| **Ecosystem** | Mature libraries for card-game interactivity: `framer-motion` (animations), `dnd-kit` (drag-and-drop), and `zustand`/`useReducer` (state management). |
| **Build tooling** | Vite provides fast HMR, simple config, and native TS support — little surface area for agent misconfiguration. |

### Stack components

> **Amendment, 2026-08-19 (EOP-40): the React major below is wrong.** `ui/package.json` pins
> `react ^18.3.1` and `@types/react ^18.3.11` — React **18**, not 19. The rest of this list holds.
> The typed `fetch` wrappers exist but live in `ui/src/api.ts`, not in a `services/` directory.
> See Amendments.

- **Language:** TypeScript (strict mode)
- **UI framework:** React 19 with functional components and hooks
- **Build tool:** Vite
- **CSS framework:** `govuk-frontend` (npm package) — CSS classes applied directly
- **State management:** React built-in (`useState`, `useReducer`, `useContext`) — external libraries added only if justified
- **HTTP client:** Native `fetch` wrapped in typed service functions
- **Testing:** Vitest + React Testing Library

### Project layout

> **Amendment, 2026-08-19 (EOP-40): the tree below is aspirational, not actual.** Of the five
> `src/` subdirectories only `components/` was built. There is no `pages/`, `hooks/`, `services/`
> or `types/`, and no `public/`; the real tree also has `assets/` and `utils/`, and the entry
> document is `ui/index.html` at the package root. See Amendments for the shipped layout.

```
ui/
  src/
    components/       # Reusable GOV.UK-style components
    pages/            # Route-level views (lobby, game board, results)
    hooks/            # Custom React hooks
    services/         # Typed API client (fetch wrappers)
    types/            # Shared TypeScript interfaces
  public/
  vite.config.ts
  tsconfig.json
  package.json
```

### Development workflow

> **Amendment, 2026-08-19 (EOP-40): the port is `:5371`, not `:5173`.** `ui/vite.config.ts` sets
> `server.port` explicitly and is the only source of truth for it. The proxy covers `/api` **and**
> `/health`. See Amendments.

The Vite dev server proxies `/api/*` requests to Spring Boot on `:8080`, so the front-end runs on `:5173` and the back-end on `:8080` during development with no CORS issues.

### AI agent implications

> **Amendment, 2026-08-19 (EOP-40): the contract anchor is a module, not a directory.** The DTO
> interfaces are in `ui/src/api.ts`; `ui/src/types/` does not exist. The intent below is honoured,
> the location is not. Same correction applies to the third Mitigation. See Amendments.

- `@ui-builder` produces `.tsx` components using functional patterns
- TypeScript interfaces in `ui/src/types/` mirror backend DTOs — agents use these as a contract anchor
- GOV.UK classes (`govuk-button`, `govuk-input`, etc.) are applied directly in JSX
- The UI builder agent must never generate class-based components or lifecycle methods

## Consequences

### Positive

- Agents write React code more correctly than any other framework — lower review burden
- TypeScript catches type errors before runtime — especially valuable for AI-generated API calls
- `govuk-frontend` CSS classes apply directly — no abstraction layer
- Vite is simple enough that agents rarely misconfigure it
- Accessible to a wide range of AI models, not just those specialised in niche frameworks

### Negative

- JSX embeds markup in TypeScript, which can make GOV.UK HTML structure slightly harder to read than template-based approaches
- React's functional + hooks model differs from Java's class-based OOP — but this trade-off is outweighed by agent training data quality
- No built-in page template from `govuk-frontend` (it provides Nunjucks macros, not React components) — the GOV.UK page layout must be replicated as a React component

### Mitigations

- The UI builder agent's rules specify GOV.UK CSS class usage and GOV.UK page template structure
- A base `GovUkPage` layout component will be created during bootstrapping that wraps `<head>`, skip link, header, footer, and main content area — this stays consistent across all pages
- TypeScript interfaces shared between API DTOs and front-end types serve as the contract anchor for agents on both sides

> **Amendment, 2026-08-19 (EOP-40): the second mitigation was never built as a component, and the
> third stands in a different place and by hand.** There is no `GovUkPage`, and the page furniture it
> lists is split across two files rather than one: the `<head>` and skip link are in `ui/index.html`,
> the header, footer and main content area are inline in `ui/src/App.tsx`. The shared interfaces were
> never placed in a `types/` directory; they are in `ui/src/api.ts`, hand-maintained rather than
> generated from the OpenAPI contract. See Amendments.
>
> **Amended again the same day (EOP-105): still hand-maintained, no longer unenforced.** Codegen was
> considered and rejected; `EnumMirrorParityTest` now fails `verify` if the mirrored enum members in
> `ui/src/api.ts` disagree with the Java enums or with `docs/api/openapi.yml`. The third mitigation
> is a contract anchor a build gate holds in place, for enum members. See the EOP-105 amendment.
>
> **Amended a third time the same day (EOP-108): the anchor is now exercised at runtime, not only
> compared at build time.** The interfaces are still hand-maintained and still in `ui/src/api.ts`,
> but the ten JSON-returning helpers now *parse* their responses through hand-written per-DTO
> parsers instead of asserting `as SomeDto`, so the mirrored enum members are load-bearing against
> live payloads. Codegen was re-evaluated as ADR-045 required and is still declined. See the EOP-108
> amendment.

## Amendments

**Amendment, 2026-08-19 (EOP-40): five claims corrected against the shipped code.**

This ADR was written on 2026-07-26, before `ui/` existed. The decision it records — React +
TypeScript + Vite + `govuk-frontend` CSS — was executed and holds. Five of its *descriptive*
claims about how that would look were never true of the code that shipped, and one of them
(the React major) is a version claim an agent could act on. The original text above is left
intact as the historical record, per the house convention; this section is what is true.

| Claim in this ADR | Reality | Evidence |
|---|---|---|
| "React **19** with functional components and hooks" (Stack components) | React **18** | `ui/package.json` — `react ^18.3.1`, `react-dom ^18.3.1`, `@types/react ^18.3.11` |
| `src/` has `components/`, `pages/`, `hooks/`, `services/`, `types/`, plus `public/` (Project layout) | Only `components/` was built. No `pages/`, `hooks/`, `services/`, `types/` or `public/`. The tree also has `assets/` and `utils/`, which the ADR does not list | `git ls-files ui/`; `ui/src/` |
| "the front-end runs on `:5173`" (Development workflow) | `:5371`, and the proxy covers `/api` **and** `/health` | `ui/vite.config.ts` — `server.port: 5371`, `server.proxy` keys `/api` and `/health` |
| "TypeScript interfaces in `ui/src/types/` mirror backend DTOs" (AI agent implications, and repeated as the third Mitigation) | The interfaces exist and do serve as the contract anchor, but they are in a single module, not a directory — and they are hand-maintained rather than generated from `docs/api/openapi.yml`, so the anchor holds only as long as someone keeps the two in step. **Superseded in part the same day by EOP-105:** the enum members are now held in step by `EnumMirrorParityTest` rather than by diligence; the rest of each DTO shape is still unguarded. **Superseded further the same day by EOP-108:** each shape is now checked at runtime by a hand-written parser (ADR-045) — object-ness, required-field presence and `typeof`, arrays, and enum membership via the `is*` guards — so "unguarded" is no longer accurate. What remains unguarded is *parser field coverage*: a field added to an interface but not to its parser is silently dropped, and only review catches it | `ui/src/api.ts` — `Card`, `PagedResponse<T>`, `SessionStateDto`, `HandDto`, `TrickStateDto`, … alongside the typed `fetch` wrappers |
| "A base `GovUkPage` layout component will be created during bootstrapping" (second Mitigation) | No such component exists, and the five items the mitigation lists are split across **two** files rather than gathered into one — which is why no single component emerged. The `<head>` and the skip link are in the Vite entry document; the header, footer and main content area are inline in the root component. The consistency the mitigation aimed at is achieved without the named abstraction | `git ls-files ui/src/components/` lists no `GovUkPage`, and `grep -rn govuk-skip-link ui/src/` returns nothing; `ui/index.html:14` carries `govuk-skip-link`; `ui/src/App.tsx` carries `govuk-header` (:210), `govuk-footer` (:222) and `govuk-main-wrapper` / `id="main-content"` (:111, :127, :258) |

### The shipped layout

```
ui/
  index.html            # entry document (no public/)
  package.json          # react 18, typescript 5.6, vite 5.4, govuk-frontend 5.7, engines.node >=22
  package-lock.json
  tsconfig.json         # strict: true
  vite.config.ts        # dev server :5371, proxies /api + /health to :8080, build → dist/
  eslint.config.js
  Dockerfile            # Node build stage → Caddy serve stage
  Caddyfile             # single origin: static assets + /api + /health (ADR-017)
  scripts/
    copy-govuk-assets.mjs   # wired as the prebuild/predev npm scripts
  src/
    main.tsx            # entry
    App.tsx
    api.ts              # DTO interfaces + typed fetch wrappers (the contract anchor)
    components/         # GOV.UK-styled screens and forms, co-located *.test.tsx
    utils/              # cardImagePath.ts
    assets/cards/       # 68 card images (EOP-66)
    setupTests.ts
    vite-env.d.ts
```

### What did not change

- The decision itself: React + TypeScript + Vite + `govuk-frontend` CSS, TypeScript strict mode,
  React built-in state management with no external state library, Vitest + React Testing Library.
  All four are verifiable in `ui/package.json` and `ui/tsconfig.json`
- The single-origin claim in Development workflow. There are no CORS issues, and the reason
  survives into production: `ui/Caddyfile` serves assets and forwards `/api` and `/health` from
  one origin (ADR-017)
- The `Implemented?` cell for this ADR in `docs/adr/README.md` — `Yes — ui/ scaffolded, built and
  served` — which is correct and was verified against the filesystem, not against prose. Only the
  Status cell moved, and only because `AdrIndexConsistencyTest` requires every ISO date in an ADR's
  status line to appear in its index row

### Why the front-end port is `:5371`

`5371` is a digit transposition of Vite's default `5173`, and is very likely a typo in the original
commit. It is nonetheless what the code does, so every document now says `:5371`. Changing the port
is a code change, out of scope for a documentation-truth story, and is raised separately.

### Amendment — EOP-105 (2026-08-19): the DTO mirror stays hand-written, and a test now holds it to the contract

The EOP-40 amendment above recorded that the DTO layer is "hand-maintained rather than generated from
`docs/api/openapi.yml`, so the anchor holds only as long as someone keeps the two in step". That
sentence named an unenforced manual invariant and left it unmanaged. Within the same review cycle the
risk it named turned out to have already materialised, so EOP-105 closes it out with a decision.

#### Context — the drift, and why nothing caught it

`ui/src/api.ts` had diverged from the Java enums in four places:

| Mirror | What `api.ts` declared | What the server emits |
|---|---|---|
| `PlayerDto.role` | `'FACILITATOR' \| 'PLAYER'` | `org.maglez.eop.entity.PlayerRole` — `FACILITATOR`, `PARTICIPANT` |
| `SessionStateDto.status` | `'LOBBY' \| 'IN_PROGRESS' \| 'ENDED'` | `SessionStatus` — `LOBBY`, `IN_PROGRESS`, `COMPLETED`, `ABANDONED` |
| `PlayerDto.connectionStatus` | bare `string` | `ConnectionStatus` — `CONNECTED`, `DISCONNECTED` |
| `LeaderboardDto.sessionStatus` | bare `string` | `SessionStatus`, as above |

So `'PLAYER'` and `'ENDED'` were values the server has never emitted, while `PARTICIPANT` — emitted
for every non-facilitator — and `COMPLETED` were absent from the union a component would branch on.

Two things about this failure mode drive the decision that follows:

- **`docs/api/openapi.yml` was correct.** Its `enum:` lists agreed with the Java enums exactly. The
  contract-first practice of [ADR-004](ADR-004-api-contract-first.md) was not at fault; the *mirror*
  of it was. Drift was one-sided, front-end only.
- **TypeScript could not fail on it.** A union of string literals is erased at compile time, and
  every fetch helper in `api.ts` ends `return (await response.json()) as SomeDto` — an assertion, not
  a parse. Nothing validates the payload, so a comparison against a value the server actually sends
  silently evaluates false and a branch quietly never runs. `tsc` being green says nothing here, and
  the "TypeScript guardrails" row in the Decision table above must not be read as covering this: it
  guards the shapes the code *asserts*, not the shapes the server *sends*.

#### Decision 1 — reject generating the mirror from OpenAPI, for now

| Option | Why not chosen |
|---|---|
| **Generate `api.ts` types from `docs/api/openapi.yml` and commit the output** | A new build-time dependency in `ui/` and a generator step in the `ui` pipeline, for supply-chain surface and a review burden on generated diffs. The drift it prevents is a three-enum, roughly twenty-line surface |
| **Generate in CI without committing** | Same dependency, plus the types no longer exist in a fresh checkout — `npm run typecheck` would depend on a generator having run, and an agent reading the repo could not see the contract anchor at all |
| **Hand-write, and enforce parity with a text-comparison test** (chosen) | Zero new dependencies. Catches the exact defect class at `verify` time. Costs one row per mirrored enum |

The mirror is small, and the generator is not free. Adopting one would also be a stack decision in
its own right, requiring its own ADR rather than an amendment to this one.

> **Re-evaluated the same day (EOP-108): the third revisit trigger below has fired, and the
> rejection is nevertheless upheld — but on narrower grounds than the ones tabulated above.** The
> table's argument was that the protected surface is "a three-enum, roughly twenty-line surface".
> That argument no longer holds: the surface is now twelve parsers over eleven DTO shapes. The
> rejection now rests on the second row instead — a generator is a stack decision needing its own
> ADR, and the parsers it would replace are already written and tested. See the EOP-108 amendment
> for the full re-evaluation.

#### Decision 2 — the manual invariant becomes a build gate

`src/test/java/org/maglez/eop/docs/EnumMirrorParityTest.java` asserts **three-way** parity by reading
repository files as text: the constants of the Java enum, the `enum:` list in `docs/api/openapi.yml`,
and the `as const` array in `ui/src/api.ts`. Two `@ParameterizedTest`s (Java against OpenAPI, Java
against TypeScript) over four mirrors give eight cases. It follows the house practice established by
`TrickPlayExceptionOriginTest` (EOP-14), `AdrIndexConsistencyTest` (EOP-32) and
`DeckArithmeticClaimsTest` (EOP-93), and is recorded as a gate in
[ADR-006](ADR-006-build-quality-gates.md).

Two scoping choices are deliberate:

- **Membership, not order.** `containsExactlyInAnyOrderElementsOf`, so re-alphabetising a YAML block
  or reordering an `as const` array does not fail the build. A guard that fires on harmless edits is
  a guard that gets deleted rather than obeyed.
- **The helpers throw rather than pass vacuously.** A missing enum, a missing schema, an empty
  `enum:` list, a missing or empty `as const` array all raise instead of yielding an empty set that
  would trivially match another empty set — the anti-vacuity rule ADR-006 records.

The mirrored unions in `api.ts` are now **derived** from runtime data rather than written twice:
`PLAYER_ROLES` / `SESSION_STATUSES` / `CONNECTION_STATUSES` are `as const` arrays, each union is
`(typeof ARRAY)[number]`, and each has a type guard (`isPlayerRole`, `isSessionStatus`,
`isConnectionStatus`) so a boundary value *can* be checked at runtime where a caller chooses to.
`ui/src/api.test.ts` covers the browser side: every member accepted, and `'PLAYER'`, `'ENDED'`, case
variants and non-strings rejected.

#### Decision 3 — the cross-artefact guard is a Java test, not a Vitest case

The natural home for a check about `ui/src/api.ts` looks like `ui/`, and it is not, for a reason
worth stating so nobody "fixes" it: `ui/` deliberately has no `@types/node`, and `ui/vite.config.ts`
deliberately avoids `process.env`, so that a browser project does not pull Node type definitions into
its type space. A Vitest case therefore cannot read `openapi.yml` or a Java source off disk. The two
duties are split accordingly — Vitest owns what the browser can see, the Java test owns what only a
process with filesystem access can compare. Keeping the guard in Vitest would have meant giving `ui/`
Node types, which is a larger change to this ADR's stack than the guard is worth.

#### Decision 4 — `view.screen` owns the client lifecycle; `session.status` gates and exits, never dispatches

Correcting the `SessionStatus` union made `COMPLETED` representable on the client for the first time,
which immediately raised a question the code had never had to answer: the session lifecycle is now
held in two places — the server's `SessionStatus` and `App.tsx`'s `view.screen` discriminated union —
and nothing said which is authoritative.

They are not redundant. `view.screen` carries states the server has no word for (`home`, `create`,
`join`), so collapsing the two is not on the table. The rule adopted instead:

> `view.screen` is the client's own state machine, and it is the only thing that decides which screen
> is rendered. `session.status` is consulted for two purposes and no others: to **gate what a screen
> renders while it is mounted**, and to **leave** a screen the server has moved past. It is never the
> subject of a lookup that maps a server state onto a screen name.

Two clarifications, because the first draft of this rule said "never to decide which screen to enter
next" and that absolute is falsified by the code eight lines from the branch this Decision is about.

**Leaving a screen names where you land, and that is not a violation.** `LobbyScreen` reads
`status === 'IN_PROGRESS'` and calls `onGameStarted`, which lands at `App.tsx`'s `setView({ screen:
'game' })` — gated on `VITE_GAME_SCREEN_ENABLED`, whose container default is `false`, so with the flag
off the read leaves nothing and the lobby stays mounted showing its started-notice. When it is on, a
server state does, transitively, name the next screen. The rule tolerates this
because it is one transition seen from the other side: the lobby is leaving, and a screen being left
has to say what replaces it. What the rule forbids is the other shape — a `switch` on
`session.status` that decides which screen to render, which would put the server in charge of the
client's state machine and make every new server state a rendering decision.

**Gating a render is not exiting.** `LobbyScreen` also reads `status` twice without leaving: the
facilitator's Start button is gated on `=== 'LOBBY'` and the "game has started" notice on
`=== 'IN_PROGRESS'`. Both are permitted, and both are load-bearing for the second-order note below —
the "inert beats misleading" argument works *because* those gates are positively scoped, so an
unknown or terminal status renders nothing rather than something. Rewriting either as a negative test
(`!== 'COMPLETED'`) would quietly cost that property.

That is why `App.tsx` still enters `game-over` through `GameScreen`'s `onGameOver` callback rather
than by observing `COMPLETED`: `App.tsx` reads `session.status` nowhere at all, and the screen it
enters is driven by an SSE event, not by a status lookup.

The visible consequence is that `COMPLETED` has **two destinations**, chosen by which component
observes it: a player who was playing lands on `game-over` with the leaderboard; a player still in the
lobby lands on `home` with their stored token cleared. This looks like an inconsistency and is a
deliberate asymmetry — a lobby-bound player made no plays and has no leaderboard row, so a score
screen would show them nothing. Recorded because it was previously undocumented and reads as a bug to
anyone who finds the two paths without this paragraph. The full table is in `runtime-view.md` under
"What the client does with `COMPLETED`".

A second-order note, since it decides where a future contributor puts new code: because `LobbyScreen`
commits `setSession`/`setError` *before* testing for the terminal status, a failure to unmount would
render a lobby that is inert (no Start button, gated on `LOBBY`; no "game has started" notice, gated
on `IN_PROGRESS`) rather than one that is misleading. Returning before committing state would leave
the *previous* render on screen — for a facilitator, a live Start button for a session that is over.
Inert beats misleading, so the ordering is intentional.

#### Known limitation — the guard cannot know about a fifth enum

`EnumMirrorParityTest.mirrors()` enumerates four mirrors. **Nothing detects a fifth mirrored enum
being added to `api.ts` without a corresponding row there.** The test keeps the mirrors it is told
about honest; it does not assert that it has been told about all of them. Adding a Java enum that the
API exposes and the front end branches on means adding a row by hand, and a reviewer is the only
thing that enforces that. This is a strictly smaller hole than the one it replaces — an unenforced
invariant over every mirrored value became an unenforced invariant over the *registration* of new
mirrors — but it is a hole, and a green build is not proof that the mirror is complete.

**This is not a hypothetical, and the count above is the evidence.** When first written the test
registered three mirrors, and this section said so. The gate review of EOP-105 then found a *fourth*
mirror sitting in the same file — `StrideCategory`, declared as a bare union whose comment asserted
"matching the server's enum exactly" while nothing checked it, which is precisely the unenforced
invariant that let `PlayerDto.role` drift in the first place. It had not yet drifted. It was
aggravated by `SUIT_LABELS` being keyed exhaustively off that union, so a drifted member would have
taken the label map with it while `tsc` stayed green. It is now converted to the `as const` idiom and
registered, taking the suite from six cases to eight. The hole fired within one review cycle of being
documented as a possibility, which is the strongest argument available for revisiting codegen once
the surface grows again.

#### Known limitation — the guard only sees one declaration idiom

`typeScriptArrayValues` matches `export const NAME = [...] as const;`. **A mirror written as a bare
union cannot be registered at all** — adding a `mirrors()` row for one throws "exports no `as const`
array named", so registration requires first rewriting the declaration. That is a real cost, not a
formality: it is why closing the `StrideCategory` gap above changed `api.ts` as well as the test.
Anyone planning to bring a further mirror under the gate should budget for the source change.

Two enums in `docs/api/openapi.yml` remain deliberately outside the gate, both because there is
nothing client-side to compare rather than because they were overlooked:

- **`Rank`** has no TypeScript mirror, and **EOP-109 decided it should stay that way** rather than
  adding one by default. `Card.rank` and `CardDto.rank` are bare `string` because the client only ever
  *displays* a rank; it never compares or orders one, so a narrowed type would protect no branch — the
  trick-winning arithmetic that does depend on rank ordering runs server-side. `CardDto.rank` is
  additionally faithful rather than drifted: `CardDto` declares `rank` as `type: string`, not a `$ref`
  to `Rank`, so bare `string` is what the contract asks for there. Registering the mirror would also
  require teaching the parser YAML *flow* sequences, since `Rank`'s `enum:` is written inline on one
  line rather than as a block list and `ENUM_KEY` (`^ {6}enum:\s*$`) cannot match that — a change to
  the gate's own parser, bought for a field with no client-side comparison to protect. Revisit it if
  rank ever becomes something the front end orders
- The response schemas carry no enums of their own

A third exclusion used to sit here for a **weaker** reason, and it is now **closed** rather than merely
re-worded: `StrideCategory`-valued fields in `ui/src/api.ts` that were declared bare `string` even
though the mirror they should have used was already under the gate. EOP-108 narrowed `CardDto.suit`,
and EOP-109 narrowed `TrickDto.ledSuit` — the last one. Every field whose contract schema is a mirrored
enum is now both typed against that mirror and membership-checked by a parser (ADR-045), so the two
bullets above *are* now a complete justification. Keep it that way: a new bare-`string` field whose
schema `$ref`s a mirrored enum belongs in neither this list nor the code.

`TrickDto.ledSuit` narrowed through a new `optionalEnum` helper rather than `requireEnum`, because the
field is genuinely optional on the wire — `TrickDto` is `@JsonInclude(NON_NULL)` server-side and
`ledSuit` is unset until the first card of a trick is played. `optionalEnum` treats absent and `null`
alike as "not present" and delegates to `requireEnum` once a value *is* present, so optionality
weakens presence but never membership.

**One related tightening was considered and declined.** `LeaderboardRowDto.capturedBySuit` is
`Readonly<Record<string, number>>` while the contract requires all six STRIDE categories, so
`Readonly<Record<StrideCategory, number>>` looks like the obvious narrowing. It was rejected because
the parser cannot back it: `requireNumberRecord` deliberately never inspects keys — the keys are
server-chosen payload, and ADR-045 forbids reflecting them into a violation message — so a narrowed
index type would be an unenforced claim, exactly the compile-time-only assertion ADR-045 exists to
remove. The laxity fails safe: `GameOverScreen.tsx` reads the map through a fixed `STRIDE_LABELS` list
with `?? 0`, so a missing or extra key renders a zero rather than misreporting a score. Revisit only
together with key validation that can report a violation without echoing the key.

`QUOTED` was widened to accept both quote styles for the same reason the idiom limitation matters:
`api.ts` uses double quotes in its card-catalogue half and single quotes in its session half, and a
one-style parser would have forced a mirror to break its neighbours' formatting to become checkable.

#### When to revisit codegen

Reopen the Decision 1 rejection if any of these hold:

- The mirrored surface grows beyond a handful of enums, or starts covering whole DTO *shapes* —
  field names, optionality, nesting — rather than enum members. A text comparison does not scale to
  shapes, and per-mirror hand-registration stops being cheap
- The registration hole above fires *again*. It has now fired once — `StrideCategory` shipped
  unregistered, and was caught by review rather than by the build. It was caught before it drifted,
  so the trigger's original conjunctive wording ("ships unregistered **and** drifts") was not
  strictly met; that wording was too generous, and this is the honest restatement. A second
  occurrence should be read as the mechanism failing rather than as bad luck
- Runtime validation of responses is adopted (a parse rather than an assertion), at which point a
  generated schema and a generated type are the same artefact and the dependency buys two things.
  **This trigger fired on 2026-08-19 (EOP-108).** It did not tip the decision, and the EOP-108
  amendment records why — but it is now spent, so it cannot be cited a second time. The first
  trigger has also effectively fired with it: the guarded surface is DTO *shapes* now, not enum
  members. What is left un-fired is the second (a second registration-hole occurrence), and one
  new trigger the parsers introduce, stated in the EOP-108 amendment


#### What this changes in the text above

The third Mitigation and its row in the EOP-40 table are annotated in place rather than rewritten:
the claim that the anchor "holds only as long as someone keeps the two in step" is now true only of
DTO shapes, not of the four mirrored enums, whose members are held in step by a build gate.

### Amendment — EOP-108 (2026-08-19): codegen re-evaluated because the parse landed, and still declined

ADR-045 adopted runtime validation of responses: the ten JSON-returning helpers in `ui/src/api.ts`
now parse their bodies through hand-written per-DTO parsers instead of `as SomeDto`. That is
precisely the third of the three "When to revisit codegen" triggers above, and ADR-045's fifth
acceptance criterion required this ADR to re-evaluate rather than let the trigger pass unremarked.

**Decision: the Decision 1 rejection is upheld. No generator is adopted.**

The re-evaluation, honestly stated:

| What the trigger predicted | What is actually true now |
|---|---|
| A generated schema and a generated type become one artefact, so the dependency buys two things instead of one | Correct, and it is the strongest form of the codegen case yet available. Twelve parsers and eleven interfaces are two hand-written expressions of one contract, and a generator would collapse them |
| The protected surface stays small enough for hand-writing to be cheap | **No longer true.** Decision 1's table justified hand-writing partly by "a three-enum, roughly twenty-line surface". `ui/src/api.ts` went from 525 to 883 lines, most of it parsers. That specific argument is spent and must not be recycled |
| — | A *new* hazard exists that codegen would remove: because a parser reconstructs its object from the fields it reads, a field added to an interface but not to its parser is **dropped** and arrives `undefined`, while still typechecking. ADR-045 records this as its principal negative. Nothing detects it today; review does — but see the correction below: codegen is **not** the only mechanism that would remove it |

Why the decision nevertheless stands:

- **A generator is a stack decision, not an amendment.** That was Decision 1's second and more
  durable ground and it is untouched by the trigger firing. Introducing one means a new `ui/`
  dependency, a generator step in the `ui` CI job, and a policy on whether output is committed —
  each of which needs its own ADR, and none of which EOP-108's scope covers.
- **The work a generator would have saved is already done.** The parsers exist, are tested by 42
  new Vitest cases, and `npm run verify` is green. Adopting codegen now would mean deleting working,
  reviewed code to reintroduce a dependency — a rewrite, not a saving. **This is the weakest of the
  four grounds** — see the self-reinforcing caveat at the end of this amendment.
- **EOP-110 is open against the `ui/` dev toolchain** (six CVEs, one critical). Adding a generator to
  that toolchain while its existing supply chain is under remediation is the wrong order.
- **The parsers are hand-written *deliberately*, per ADR-045 §1**, not as a stopgap. Reversing that
  belongs in a superseding ADR, not in an amendment written the same day.

**What this leaves for next time.** Two of the three original triggers are now spent — shapes are
guarded (trigger 1) and a parse exists (trigger 3) — so a future argument for codegen cannot lean on
either. It should lean on the new trigger this amendment adds instead:

- **A parser field-coverage defect reaches `main`** — a field present in a DTO interface, absent from
  its parser, and therefore silently dropped at runtime. Review has already been shown to miss
  defects of exactly this shape once (EOP-105's unregistered `StrideCategory` mirror), and unlike
  the spent triggers it would be an observed failure rather than a predicted one. One occurrence
  should reopen Decision 1 with the presumption reversed.

**A correction, because the option set here is three wide and not two.** An earlier draft of this
amendment called field-coverage "the defect class only a generator removes". That is false in *this*
repository, and the error mattered enough to fix in place rather than leave for a reader to quote:
`src/test/java/org/maglez/eop/docs/` already runs four gates that read source and Markdown **as
text** and compare two extracted sets — `EnumMirrorParityTest`, `AdrIndexConsistencyTest`,
`DeckArithmeticClaimsTest` and `TrickPlayExceptionOriginTest`. A fifth in the same idiom would close
field coverage with no generator, no `ui/` dependency and no contact with EOP-110's supply-chain
remediation. Concretely it would read `ui/src/api.ts` as text (in Java, for the same reason
`EnumMirrorParityTest` is a Java test: `ui/` deliberately has no `@types/node`, so nothing inside
`ui/` can read files off disk), collect for each `interface X` the declared property names and their
`?` optionality, collect for the matching `function parseX(` every string literal passed as the `key`
argument to the nine field readers plus the keys in the returned object literal and the
`...(x === undefined ? {} : { x })` spread, and assert the two sets are equal in both directions with
optional properties read by an `optional*` reader rather than a `require*` one. Registration would be
by the `interface X` ⇒ `parseX` convention rather than a hand-written list, so unlike
`EnumMirrorParityTest.mirrors()` it would not carry the unregistered-mirror hole that
`StrideCategory` fell through. It would inherit the same acknowledged limitation as its siblings —
it recognises only the idioms currently in the file — which ADR-006 already accepts as the price of
this class of gate.

So the honest statement of the choice, should the trigger fire, is between **three** options and not
two: adopt codegen, add a text-comparison build gate, or continue with review. This amendment
declines codegen on the three grounds above that survive scrutiny — a generator is a stack decision,
EOP-110 sequences ahead of it, and reversing ADR-045 §1 belongs in a superseding ADR — and it does
**not** rest on the claim that codegen is the sole remedy. The fourth ground offered above ("the work
is already done, so adopting now means deleting working code") is the weakest of the four and is
noted here as self-reinforcing: it grows stronger every time it is used and would have blocked
codegen at any point after the first parser was written, so it should not be treated as admissible a
second time in the way the other three are. Two further notes for whoever picks this up: the build
gate is the *cheaper* of the two mechanical options and should be evaluated first; and it is not
free either, since a hand-written text matcher is itself code that can drift from the idioms it
scans.

### Amendment — EOP-109 (2026-08-19): the last bare-`string` contract enum is closed, and `Rank` becomes a decision rather than a gap

EOP-105 left an exclusion list with three entries: two principled (`Rank`, and response schemas that
carry no enums of their own) and one recorded as **weaker** — `StrideCategory`-valued fields typed
bare `string` even though the mirror they needed was already registered and gated. EOP-108 closed half
of that weak entry (`CardDto.suit`); this ticket closes the other half (`TrickDto.ledSuit`) and turns
`Rank` from an unexamined omission into a recorded rejection. The "Known limitation" section above has
been rewritten *in place* to describe the position after this ticket rather than before it; this
amendment records what changed and why.

**1. `TrickDto.ledSuit` is now `StrideCategory`, still optional.** It was the last field in
`ui/src/api.ts` whose contract schema is a `$ref` to a mirrored enum but whose TypeScript type was
bare `string`, and its parser read it with `optionalString`, which admits *any* string. It now reads
through a new `optionalEnum` helper that delegates to `requireEnum` once a value is present, so the
field consumes the existing `isStrideCategory` guard rather than re-inlining the member list — the
seam `.opencode/rules/error-handling.md` requires, and the only form `EnumMirrorParityTest` can see.

Why a new helper rather than `requireEnum` directly: `ledSuit` is genuinely optional on the wire.
Server-side `TrickDto` is `@JsonInclude(NON_NULL)` and `ledSuit` is unset on a trick that has been
opened but holds no plays yet, which the contract states explicitly. `optionalEnum` therefore weakens
*presence* and nothing else — a supplied value is held to membership exactly as a required one is, and
absent and explicit `null` are treated alike, consistent with `isAbsent`'s existing meaning. Two
Vitest cases pin both halves: the shared boundary table gains a case proving an out-of-contract
`ledSuit` throws `ContractViolationError` without echoing the offending value, and an explicit test
asserts `null` is still admitted as absent (`Object.hasOwn` is `false`) while `'spoofing'` is rejected
with a message naming the DTO and field.

**2. `Rank`: mirror rejected, not deferred.** The acceptance criteria asked for a decision rather than
a default, and the decision is to leave `Rank` unmirrored. Three independent reasons, in descending
order of weight:

- **Nothing on the client compares a rank.** The front end only ever *displays* it. The trick-winning
  arithmetic, which is the only logic depending on rank *ordering*, runs server-side. A narrowed type
  would protect no branch — the same ground the other principled exclusion rests on
- **For `CardDto.rank`, bare `string` is faithful, not drifted.** `CardDto` declares `rank` as
  `type: string`, not a `$ref` to `Rank`. Narrowing it would make the client *more* specific than the
  contract: drift in the opposite direction, and a change that would need the contract amended first
- **It would cost a change to the gate's own parser.** `Rank`'s `enum:` is a one-line flow sequence,
  so `openApiEnumValues` would need teaching a second YAML form for zero behavioural gain

The exit condition is written into both this ADR and `EnumMirrorParityTest.mirrors()`: if rank ever
becomes something the front end orders or compares, add the mirror and teach the parser then.

**3. `capturedBySuit`: tightening declined, and the reason is a property of the parser.** Narrowing
`LeaderboardRowDto.capturedBySuit` to `Readonly<Record<StrideCategory, number>>` was considered and
rejected. `requireNumberRecord` deliberately never inspects keys, because the keys are server-chosen
payload and ADR-045 forbids reflecting them into a violation message; the narrowed index type would
therefore be a compile-time claim with no runtime check behind it — precisely the defect class ADR-045
was written to remove. Declining it is not deferral of work but refusal to reintroduce an unenforced
assertion. The condition for revisiting is recorded in the section above.

**What this closes.** The exclusion list is now complete on its own terms: every field in
`ui/src/api.ts` whose contract schema is a mirrored enum is both typed against that mirror and
membership-checked by a parser. No new `mirrors()` row was needed — `STRIDE_CATEGORIES` was already
registered — so EOP-109's third acceptance criterion is satisfied *vacuously*. That is worth stating
explicitly, because "no row was needed" and "a row was forgotten" look identical in a diff, and the
unregistered-`StrideCategory` hole found during EOP-105 is exactly what that looks like when it goes
wrong.

## Related

- [ADR-004: API Contract-First](ADR-004-api-contract-first.md) — OpenAPI contract drives shared types
- [ADR-006: Build Quality Gates](ADR-006-build-quality-gates.md) — records `EnumMirrorParityTest` as
  a documentation-integrity gate
- [ADR-045: Front-End Response Validation](ADR-045-frontend-response-validation.md) — adopts the
  hand-written per-DTO parsers that fired this ADR's third codegen revisit trigger (EOP-108)

- GOV.UK Design System: https://design-system.service.gov.uk/
- GOV.UK Frontend: https://frontend.design-system.service.gov.uk/
