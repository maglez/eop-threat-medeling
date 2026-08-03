# PRD: Elevation of Privilege threat modelling card game

**Status:** Draft — several assumptions are unconfirmed and are labelled as such
**Date:** 2026-08-03
**Epic:** [EOP-5](https://maglez.atlassian.net/browse/EOP-5)
**Author:** `@team-member-product-owner`, reviewed and corrected by `@team-member-tech-lead`

This document is the source of truth for what the application is meant to do. Where it
disagrees with a Jira story, this document wins and the story is wrong. Where it records an
assumption rather than a decision, the assumption is labelled and listed in section 7.

---

## 1. Problem statement

Threat modelling works best as a collaborative activity with a shared vocabulary. Microsoft's
*Elevation of Privilege* card game provides exactly that: STRIDE as the vocabulary, one threat
prompt per card, and a turn-taking mechanic that forces every category to get discussed rather
than letting the loudest person in the room set the agenda.

The original game needs a physical deck and a shared table. Distributed teams have neither.

This application is the digital equivalent of that table: a facilitated, real-time, multiplayer
session that a development team plays together during a design review. It is deliberately **not**
a training course, a STRIDE reference guide, or a solo exercise. If a single person can get value
from it alone, we have built the wrong thing.

---

## 2. Who it serves

**Primary — the participating developer or reviewer.** Joins a session from their own machine
during a scheduled design review. May know nothing about STRIDE. Needs to understand what threat
a card is asking them to consider, discuss it, and move on. Is not a security specialist and
should not need to be.

**Secondary — the session facilitator.** Creates the session, shares the join code, and drives
the pace. In a small team this is the same person as a participant.

**Not served by this product:** formal threat-model audit workflows, enterprise compliance
reporting, integration with OWASP Threat Dragon or the Microsoft Threat Modeling Tool, and
anyone wanting a persistent account with history across sessions.

---

## 3. Journeys

### 3.1 The facilitator creates a session

1. Opens the application.
2. Enters a display name and creates a session.
3. Receives a short join code and a shareable URL.
4. Shares it with the team.
5. Waits in the lobby, watching players arrive, then starts the game.

### 3.2 A player joins

1. Opens the shared URL.
2. Enters a display name.
3. Sees the lobby — who else is here, and that the game has not started.
4. When the facilitator starts, the view becomes the game board.

### 3.3 Playing a round — [ASSUMPTION A]

1. The active player draws a card. It shows a STRIDE suit and a threat prompt.
2. The active player names a component of the system under discussion.
3. The group discusses whether that threat applies to that component.
4. The card is marked discussed. A short note may be recorded.
5. The turn passes.
6. The game ends when the deck is exhausted or the facilitator ends it.

The system being threat-modelled is **not** represented inside the application — see
[ASSUMPTION B]. Players look at their own architecture diagram on a shared screen. All this
application stores is the free-text component name typed when a card is played.

### 3.4 Resuming

A player refreshes the browser, loses connectivity, or returns after the server restarts. They
return to the same session as the same player, and see the current state: whose turn it is,
which cards have been played, and the notes recorded against them.

---

## 4. Scope

### In scope

- Multiplayer sessions of 2–8 players — [ASSUMPTION C]
- Anonymous identity: a display name only. No password, no account, no email.
- A placeholder deck of six cards, one per STRIDE category, standing in for the real deck
- Game state persisted in PostgreSQL: session, players, deck order, played cards
- Real-time state synchronisation to every connected player
- Resume after a browser refresh, a lost connection, or a server restart
- A React + TypeScript + Vite front end under `ui/`, served behind a reverse proxy
- Plain HTTP on a bare IP address — no TLS, no domain (ADR-012 records why)
- Deployment to the EC2 instance already described by the Terraform in `infra/`

### Out of scope

- Accounts, registration, login, authentication of any kind
- History across sessions — a finished game is not browsable afterwards
- Integration with external threat modelling tools
- A system-model or diagram editor inside the application
- Scoring or leaderboards beyond "discussed / not discussed"
- TLS and HTTPS, until a domain exists
- Swagger UI on the deployed instance — disabled in the `prod` profile by ADR-012
- Full WCAG 2.2 AA conformance — best effort only, and recorded as a gap
- A native mobile application. Responsive web is sufficient.

---

## 5. Domain concepts

```
Session                        (aggregate root; table: game_session)
  sessionCode                  short, unguessable, used to join
  status                       LOBBY | IN_PROGRESS | COMPLETED | ABANDONED
  createdAt, updatedAt
  players                      1:N

Player
  playerId                     UUID
  displayName                  free text, validated and bounded
  role                         FACILITATOR | PARTICIPANT
  connectionStatus             CONNECTED | DISCONNECTED

Deck                           (within a Session; 1:1)
  the shuffled, ordered list of cards for that session

Card                           (reference data; seeded once, never mutated during play)
  cardId                       UUID
  strideCategory               the existing StrideCategory enum
  cardNumber                   sequential within suit — [ASSUMPTION D]
  threatPrompt                 the text the group discusses

PlayedCard                     (1:N from Session)
  playedCardId                 UUID
  session, card, playedBy
  componentName                free text — the thing being threatened
  notes                        optional
  playedAt

GameState                      (within a Session)
  currentTurn                  which Player acts next
  remainingDeck                ordered
  playedCards
```

`Card` is reference data and is never mutated by gameplay. Everything else is session-scoped
and is discarded when retention is eventually defined — see R6.

The table is named `game_session` rather than `session`, because `session` collides with
HTTP-session vocabulary and with reserved or near-reserved identifiers in several databases.

---

## 6. Open risks

| # | Risk | Owner | Blocks |
|---|------|-------|--------|
| R1 | **The deck's licence is unverified.** It has been claimed the Microsoft deck is 74 cards under Creative Commons Attribution 3.0. Neither the count nor the licence has been checked against Microsoft's own published source. This repository is public, so getting it wrong is a real problem, not a formality. | Owner | EOP-13 only. Deliberately nothing else. |
| R2 | **No AWS account exists.** `terraform apply` has never run. Nothing is demonstrable at a public IP. | Owner | EOP-7 only. |
| R3 | **Real-time transport undecided.** Server-sent events, WebSocket, or polling. | Tech Lead | EOP-10, EOP-12 |
| R4 | **Player identity undecided.** Even anonymous multiplayer needs a durable credential answering "am I that player?" across a refresh. | Tech Lead | EOP-10, EOP-12 |
| R5 | **Concurrency control undecided.** Two players acting at once must not both receive the same card. Optimistic locking is the likely answer; the semantics are not decided. | Tech Lead | EOP-12 |
| R6 | **Session lifecycle and storage growth undefined.** Sessions accumulate forever on a t3.small with a 10 GB data volume. No expiry, archival, or deletion policy exists. | Owner + Tech Lead | Production readiness |
| R7 | **Card fields beyond suit, number and prompt are unconfirmed.** If the real deck carries a score value, difficulty, or example mitigations, the first Liquibase changeset needs revising. | Owner | EOP-6 schema |
| R8 | **Round mechanics unconfirmed.** [ASSUMPTION A] stands in. Scoring, a different turn order, or a different end condition would change EOP-12. | Owner | EOP-12 |
| R9 | **Whether the modelled system belongs in the app is unconfirmed.** [ASSUMPTION B] stands in. A component editor would be a new epic, not a change to an existing story. | Owner | Nothing today; scope risk later |

---

## 7. Assumptions

These are assumptions, not decisions. Each one is a guess that has not been confirmed by the
owner, and each is cheap to correct now and expensive to correct after EOP-12 is built.

**[ASSUMPTION A] — round mechanics.** Draw, name a component, discuss, mark discussed, pass the
turn. No scoring. The game ends when the deck is exhausted or the facilitator ends it.

**[ASSUMPTION B] — the system model is not in the application.** Players use their own diagram.
The only system-model data stored is the free-text component name.

**[ASSUMPTION C] — 2 to 8 players.** A soft limit. The real limit is instance memory and the
number of live connections a t3.small will hold.

**[ASSUMPTION D] — card numbering is sequential within each STRIDE suit,** matching the original
deck's convention. If the real deck numbers differently this is a data-only change.

**[ASSUMPTION E] — turn order is round-robin in join order, facilitator first.**

**[ASSUMPTION F] — a reverse proxy serves the built front-end assets and proxies the API.**
Caddy is the likely choice, because it obtains and renews TLS certificates automatically, which
turns the accepted no-TLS limitation into a one-line change the day a domain exists.

> **Correction on the record:** no ADR selects a reverse proxy today. ADR-009 chooses React,
> TypeScript, Vite and GOV.UK Frontend and says nothing about a proxy; the only mention anywhere
> in the ADRs is an aside in ADR-012. The topology therefore needs its own decision record
> (number 016) before EOP-9 starts. An earlier draft of the backlog cited "ADR-009 (Caddy, not
> nginx)" as a hard constraint. That was false, and it is recorded here so nobody repeats it.

---

## 8. Non-functional expectations

| Concern | Expectation |
|---------|-------------|
| Performance | p95 under 200 ms on `/health`, per the existing k6 gate. Real-time events under 500 ms round trip on normal broadband. |
| Availability | Best effort. A single instance, no load balancer, no SLA. |
| Security | Validate every input at the boundary. Join codes must be unguessable and generated from a cryptographically secure source. No secrets in code. Default-deny. |
| Accessibility | GOV.UK Design System defaults. Full WCAG 2.2 AA is **not** claimed and is recorded as a known gap. |
| Data retention | Undefined — see R6. |
| Browsers | Modern evergreen browsers. |
| Deployment | Manual `docker compose pull && up -d` over SSH. ADR-012 records why CI does not deploy. |

### The security consequence of having no authentication

There is no authentication by design. That is a legitimate choice for a PoC whose sessions are
ephemeral and contain no personal data. It has a consequence worth stating plainly: each control
around session access is the **entire** control, not one layer of several.

So the join code must be unguessable rather than merely random-looking; join attempts must be
rate-limited; and a request for a code that never existed must be indistinguishable from one for
a code that has expired, because telling them apart is an enumeration aid.

---

## 9. Delivery order and why

The backlog is ordered by risk retired per unit of work, not by how interesting each piece is.

| Order | Story | Size | Blocked by |
|-------|-------|------|-----------|
| 1 | [EOP-6](https://maglez.atlassian.net/browse/EOP-6) Card catalogue: seed the placeholder deck, serve `GET /api/v1/cards` | M | Nothing |
| 2 | [EOP-7](https://maglez.atlassian.net/browse/EOP-7) Complete the walking skeleton: first live deployment | S | R2 (owner) |
| 3 | [EOP-8](https://maglez.atlassian.net/browse/EOP-8) Spike: real-time transport and player identity | S | Nothing |
| 4 | [EOP-9](https://maglez.atlassian.net/browse/EOP-9) UI scaffold behind a reverse proxy | M | Decision 016 |
| 5 | [EOP-10](https://maglez.atlassian.net/browse/EOP-10) Session lifecycle: create and join | L | EOP-8 output |
| 6 | [EOP-11](https://maglez.atlassian.net/browse/EOP-11) Game lobby UI | M | EOP-9, EOP-10 |
| 7 | [EOP-12](https://maglez.atlassian.net/browse/EOP-12) Play a card: the core game loop | XL | EOP-10, EOP-11, decision 017 |
| 8 | [EOP-13](https://maglez.atlassian.net/browse/EOP-13) Replace the placeholder deck with the real content | S | R1 (owner) |

The card catalogue goes first and is blocked by nothing. It is the first entity, the first
Liquibase changeset, the first hand-authored OpenAPI document and the first RFC 9457 error
handler — four pieces of scaffolding every later story inherits. It is fully provable in CI,
which already boots the application under the `prod` profile against real PostgreSQL with
Liquibase enabled and asserts `/health`. **It does not need a deployment to be finished.**

That last point is a correction to an earlier draft, which made the card catalogue depend on the
deploy story. Since the deploy story is itself blocked on an AWS account that does not exist,
that dependency would have stalled the entire backlog behind a task nobody had started. EOP-7 is
deliberately independent and gates nothing.

### Architecture decisions still required

| Number | Subject | Needed by |
|--------|---------|-----------|
| 013 | Feature-flag mechanism | EOP-6 |
| 014 | Real-time transport | EOP-10 |
| 015 | Player identity within an anonymous session | EOP-10 |
| 016 | Front-end delivery and reverse-proxy topology | EOP-9 |
| 017 | Concurrency control | EOP-12 |

For 013, a plain Spring conditional property is likely sufficient: no new dependency, and
overridable by environment variable, which is all a single-instance PoC needs.

For 014, server-sent events deserve serious consideration before WebSocket is chosen by reflex.
The traffic is overwhelmingly server-to-client, SSE introduces no new protocol, browsers
reconnect automatically by specification, and it passes through a reverse proxy with less
configuration. `spring-boot-starter-websocket` is not currently a dependency; whatever is chosen
has to justify what it adds.

---

## 10. The concern worth recording

This will run on one t3.small with no load balancer, no horizontal scaling and no session
affinity. Every deployment restarts the container, and every restart drops every connected
client.

That makes reconnection a hard functional requirement rather than a refinement. It has to be
designed before the session story is built, not retrofitted after the first demo goes quiet
mid-game.

---

## Related

- [EOP-5](https://maglez.atlassian.net/browse/EOP-5) — the epic this document describes
- `docs/adr/README.md` — the indexed architecture decisions, with implementation status
- `docs/adr/ADR-009-frontend-react-typescript.md` — front-end stack
- `docs/adr/ADR-012-deployment-target.md` — deployment target, and why there is no TLS
- `docs/adr/ADR-004-api-contract-first.md` — the contract-first obligation EOP-6 discharges
- `docs/adr/ADR-005-error-handling.md` — the RFC 9457 obligation EOP-6 discharges
