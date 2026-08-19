# ADR-009: Front-End Technology Stack — React + TypeScript + Vite + GOV.UK Frontend

- **Status:** Accepted (amended 2026-08-19 — four stack and layout claims diverged from the shipped code; see Amendments)
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

> **Amendment, 2026-08-19 (EOP-40): the third mitigation stands, in `ui/src/api.ts`.** The shared
> interfaces were never placed in a `types/` directory. See Amendments.

## Amendments

**Amendment, 2026-08-19 (EOP-40): four claims corrected against the shipped code.**

This ADR was written on 2026-07-26, before `ui/` existed. The decision it records — React +
TypeScript + Vite + `govuk-frontend` CSS — was executed and holds. Four of its *descriptive*
claims about how that would look were never true of the code that shipped, and one of them
(the React major) is a version claim an agent could act on. The original text above is left
intact as the historical record, per the house convention; this section is what is true.

| Claim in this ADR | Reality | Evidence |
|---|---|---|
| "React **19** with functional components and hooks" (Stack components) | React **18** | `ui/package.json` — `react ^18.3.1`, `react-dom ^18.3.1`, `@types/react ^18.3.11` |
| `src/` has `components/`, `pages/`, `hooks/`, `services/`, `types/`, plus `public/` (Project layout) | Only `components/` was built. No `pages/`, `hooks/`, `services/`, `types/` or `public/`. The tree also has `assets/` and `utils/`, which the ADR does not list | `git ls-files ui/`; `ui/src/` |
| "the front-end runs on `:5173`" (Development workflow) | `:5371`, and the proxy covers `/api` **and** `/health` | `ui/vite.config.ts` — `server.port: 5371`, `server.proxy` keys `/api` and `/health` |
| "TypeScript interfaces in `ui/src/types/` mirror backend DTOs" (AI agent implications, and repeated as the third Mitigation) | The interfaces exist and do serve as the contract anchor, but they are in a single module, not a directory | `ui/src/api.ts` — `Card`, `PagedResponse<T>`, `SessionStateDto`, `HandDto`, `TrickStateDto`, … alongside the typed `fetch` wrappers |

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

## Related

- [ADR-004: API Contract-First](ADR-004-api-contract-first.md) — OpenAPI contract drives shared types
- GOV.UK Design System: https://design-system.service.gov.uk/
- GOV.UK Frontend: https://frontend.design-system.service.gov.uk/
