# ADR-009: Front-End Technology Stack — React + TypeScript + Vite + GOV.UK Frontend

- **Status:** Accepted
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

- **Language:** TypeScript (strict mode)
- **UI framework:** React 19 with functional components and hooks
- **Build tool:** Vite
- **CSS framework:** `govuk-frontend` (npm package) — CSS classes applied directly
- **State management:** React built-in (`useState`, `useReducer`, `useContext`) — external libraries added only if justified
- **HTTP client:** Native `fetch` wrapped in typed service functions
- **Testing:** Vitest + React Testing Library

### Project layout

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

The Vite dev server proxies `/api/*` requests to Spring Boot on `:8080`, so the front-end runs on `:5173` and the back-end on `:8080` during development with no CORS issues.

### AI agent implications

- `@team-member-ui-builder` produces `.tsx` components using functional patterns
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

## Related

- [ADR-004: API Contract-First](ADR-004-api-contract-first.md) — OpenAPI contract drives shared types
- GOV.UK Design System: https://design-system.service.gov.uk/
- GOV.UK Frontend: https://frontend.design-system.service.gov.uk/
