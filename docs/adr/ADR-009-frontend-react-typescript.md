# ADR-009: Front-End Technology Stack — React + TypeScript + Vite + GOV.UK Frontend

- **Status:** Accepted (amended twice on 2026-08-19 — five stack and layout claims diverged from the shipped code, and the DTO mirror's unenforced manual invariant is now a build gate; see Amendments)
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
| "TypeScript interfaces in `ui/src/types/` mirror backend DTOs" (AI agent implications, and repeated as the third Mitigation) | The interfaces exist and do serve as the contract anchor, but they are in a single module, not a directory — and they are hand-maintained rather than generated from `docs/api/openapi.yml`, so the anchor holds only as long as someone keeps the two in step. **Superseded in part the same day by EOP-105:** the enum members are now held in step by `EnumMirrorParityTest` rather than by diligence; the rest of each DTO shape is still unguarded | `ui/src/api.ts` — `Card`, `PagedResponse<T>`, `SessionStateDto`, `HandDto`, `TrickStateDto`, … alongside the typed `fetch` wrappers |
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

- **`Rank`** has no TypeScript mirror. `Card.rank` and `CardDto.rank` are bare `string`, because the
  client only ever displays the value. Registering it would additionally require teaching the parser
  YAML *flow* sequences, since `Rank`'s `enum:` is written inline on one line rather than as a block
  list, and `ENUM_KEY` (`^ {6}enum:\s*$`) cannot match that
- The response schemas carry no enums of their own

One field sits outside the gate for a **weaker** reason, and it is named here so the two bullets above
are not read as a complete justification. `CardDto.suit` (`ui/src/api.ts`) is a `StrideCategory`-valued
field still declared as bare `string`, while its sibling `Card.suit` in the same file is typed against
the mirror the gate now covers. Unlike `Rank`, this is not a case of having nothing client-side to
compare — the mirror exists, and the field simply is not using it. Drift there fails safe today:
`cardImagePath` returns `null` for an unknown suit, and every consumer either null-checks or falls back
(`SUIT_COLOURS[…] ?? '#0b0c0c'`, `SUIT_LABELS[…] ?? card.suit[0]`), so an unexpected value degrades to a
missing image rather than a wrong comparison. That is why it is a follow-up rather than part of EOP-105.
It is recorded because a follow-up ticket written as "`Rank` only" would be wrong: three bare-`string`
fields remain (`Card.rank`, `CardDto.suit`, `CardDto.rank`), and only two of them have the `Rank`
excuse.

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
  generated schema and a generated type are the same artefact and the dependency buys two things


#### What this changes in the text above

The third Mitigation and its row in the EOP-40 table are annotated in place rather than rewritten:
the claim that the anchor "holds only as long as someone keeps the two in step" is now true only of
DTO shapes, not of the four mirrored enums, whose members are held in step by a build gate.

## Related

- [ADR-004: API Contract-First](ADR-004-api-contract-first.md) — OpenAPI contract drives shared types
- [ADR-006: Build Quality Gates](ADR-006-build-quality-gates.md) — records `EnumMirrorParityTest` as
  a documentation-integrity gate

- GOV.UK Design System: https://design-system.service.gov.uk/
- GOV.UK Frontend: https://frontend.design-system.service.gov.uk/
