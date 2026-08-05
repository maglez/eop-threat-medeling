# PRD: Elevation of Privilege threat modelling card game

**Status:** Game rules now sourced and corrected (see §11). Assumptions about mechanics are closed
where confirmed by primary sources. Assumptions about architecture and infrastructure remain open.
**Date:** 2026-08-03 (rules correction: 2026-08-04)
**Epic:** [EOP-5](https://maglez.atlassian.net/browse/EOP-5)
**Author:** `@product-owner`, reviewed and corrected by `@tech-lead`

This document is the source of truth for what the application is meant to do. Where it
disagrees with a Jira story, this document wins and the story is wrong. Where it records an
assumption rather than a decision, the assumption is labelled and listed in section 7.

---

## 1. Problem statement

Threat modelling works best as a collaborative activity with a shared vocabulary. Microsoft's
*Elevation of Privilege* card game provides exactly that: STRIDE as the vocabulary, one threat
prompt per card, and a trick-taking mechanic that forces every category to get discussed rather
than letting the loudest person in the room set the agenda.

The original game needs a physical deck and a shared table. Distributed teams have neither.

This application is the digital equivalent of that table: a facilitated, real-time, multiplayer
session that a development team plays together during a design review. It is deliberately **not**
a training course, a STRIDE reference guide, or a solo exercise. If a single person can get value
from it alone, we have built the wrong thing.

### The tension worth naming at the outset

The whitepaper (§3.3) records that Microsoft deliberately declined to build an online version:
"[A physical game] forces people to sit around a table to play the game in a way which reinforces
the game message, reduces distraction and encourages discussion. These advantages to a physical
game have made us reluctant to build an online version." We are building precisely the thing the
game's author chose not to build. The justification — distributed teams cannot share a physical
table — is real and sufficient. But the trade-off is named here rather than glossed.

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

### 3.3 Playing a round — trick-taking mechanic (sourced)

The game is a **trick-taking card game**, closest to Spades. The following is sourced from
`docs/EoP_Microsoft_Docs/EoP_Instructions.pdf` and `docs/EoP_Microsoft_Docs/eop_whitepaper.pdf`.

**Before play starts:** the facilitator confirms the team has an architectural diagram of the
system to be threat-modelled. This diagram is held outside the application — see §5 and
[ASSUMPTION B, now confirmed].

**Dealing:** the whole deck is dealt evenly to all players. There is no shared draw pile. Players
are encouraged to lay their cards face up, arranged by suit. The oversized physical card format
(12 cm × 7 cm) was chosen deliberately to make holding a hand awkward, so players help each
other — the digital equivalent is that each player's hand is visible on their own screen.

**Opening lead:** the holder of the **lowest-ranked Tampering card present in the deck** leads
the first trick. For the 78-card deck seeded from `cards.yaml` this is the 2 of Tampering; for
the original 74-card printed deck it is the 3 of Tampering. **Hardcoding either value is a
defect.** The rule must be derived at runtime. This starting point was deliberate: Tampering
issues are common, it gives a new player a quick first decision, and a playtest that started in
Repudiation went badly.

**A trick:** one time around the table, clockwise. The active player leads by playing a card face
up, reading it aloud, and explaining how the threat applies to the system. A player **must follow
suit** if they hold a card in the led suit. If they hold none, they may play any suit.

**Linking a threat:** playing a card where a compensating control already exists is still valid —
it lets the group discuss that control. **Critically: if the player cannot link the threat to the
system, play still proceeds.** A card may be legally played with no valid threat. It scores
nothing but does not block the trick.

**Winning a trick:** the highest card of the led suit wins — **unless one or more Elevation of
Privilege cards were played, in which case the highest EoP card wins. EoP is the trump suit.**
Only EoP or the led suit can take a trick. The winner of a trick leads the next trick.

**Aces — Open Threat cards:** each Ace reads "You've invented a new [Suit] attack." The player
must identify a threat not printed on any other card, usually prompting a discussion about whether
two threats are equivalent.

**Validity test:** when a threat causes an argument, the instruction card resolves it by asking:
*"Would we take an actionable bug, feature request or design change for that?"* If yes, it is a
real threat. Reading conventions: prompts starting "There's a way" are read as "There's a
way…and here's how…"; those starting "Your code" as "The code we're collectively creating…".

**End condition:** play continues until players run out of time, cards, or ways to connect their
threats to the system. Most points wins. Typical duration 60–90 minutes.

**Onboarding requirement:** some players — especially those from non-Western cultures — have no
card-game experience and find terms like "trick" and "led suit" unfamiliar. The physical deck
ships a rules flowchart card for exactly this reason. The UI must teach the trick-taking mechanic
in place, not assume card-game literacy. This is an explicit acceptance criterion on EOP-11.

The system being threat-modelled is **not** represented inside the application — see
[ASSUMPTION B, confirmed by source]. Players look at their own architecture diagram on a shared
screen. All this application stores is the free-text component name(s) typed when a card is played.

### 3.4 Scoring (sourced)

Shipped rule: **1 point for a threat on your card, +1 for taking the trick.**

The whitepaper notes: "The written rule says to only count the highest card which was actually
connected to the system being developed, but in practice this is sometimes discarded to give a
deeper involvement to beginners."

The simplicity is hard-won. The abandoned v0.21 system awarded 4 for a threat on your own card;
3/2/1 for the first/next/next threat on someone else's card; +1 for taking the trick; +2 for a
face card; +3 for an ace. It was scrapped because "players (even the dedicated repeat playtesters)
were looking to me at the end of every round to ascertain who scored." **Server-computed scoring
removes exactly this friction.** Richer scoring becomes cheap once the server keeps score — but
it is out of scope now.

The official Score Card (`docs/EoP_Microsoft_Docs/EoP_Score Card.pdf`) has columns:
**Name | Points | Card | Component(s) | Notes on Threat**. Component(s) is plural — one card's
threat may be linked to several components. See §5.

### 3.5 Resuming

A player refreshes the browser, loses connectivity, or returns after the server restarts. They
return to the same session as the same player, and see the current state: whose turn it is,
which trick is in progress, and the notes recorded against played cards.

---

## 4. Scope

### In scope

- Multiplayer sessions of **3–6 players (3–5 tested range)** — see [ASSUMPTION C, corrected]
- Anonymous identity: a display name only. No password, no account, no email.
- The real deck: all 78 cards, thirteen ranks in each of the six suits, © 2009 Microsoft Corporation under CC-BY-3.0 US, with attribution shown in the running application (delivered by EOP-13)
- Game state persisted in PostgreSQL: session, players, dealt hands, tricks, played cards
- Real-time state synchronisation to every connected player
- Resume after a browser refresh, a lost connection, or a server restart
- A React + TypeScript + Vite front end under `ui/`, served behind a reverse proxy
- Plain HTTP on a bare IP address — no TLS, no domain (ADR-012 records why)
- Deployment to the EC2 instance already described by the Terraform in `infra/`
- A searchable reference list of all 78 threat prompts (replaces the 6 physical reference cards
  shipped with the deck, which players use to adjudicate whether an Ace's invented threat is
  already covered)
- Server-computed scoring (removes the friction that forced the physical game's scoring
  simplification — see §3.4)

### Out of scope

- Accounts, registration, login, authentication of any kind
- History across sessions — a finished game is not browsable afterwards
- Integration with external threat modelling tools
- A system-model or diagram editor inside the application
- Leaderboards or scoring history across games. Per-game scoring itself **is** in scope, from
  EOP-15 (see §9); what is excluded is any ranking that outlives a single session.
- Optional game variants (pass cards after trick 3; double scoring; riffing; diagram mark-up) —
  known and explicitly declined for this PoC; see §7
- TLS and HTTPS, until a domain exists
- Swagger UI on the deployed instance — disabled in the `prod` profile by ADR-012
- Full WCAG 2.2 AA conformance — best effort only, and recorded as a gap
- A native mobile application. Responsive web is sufficient.

---

## 5. Domain concepts

The model below reflects the trick-taking mechanic sourced from primary documents. It must be
able to answer: whose turn is it, what suit was led, may this player legally play this card, who
won this trick, who leads next, and what does each player score.

```
Session                        (aggregate root; table: game_session)
  sessionCode                  short, unguessable, used to join
  status                       LOBBY | IN_PROGRESS | COMPLETED | ABANDONED
  createdAt, updatedAt
  players                      1:N

Player
  playerId                     UUID
  displayName                  free text, validated and bounded
  seatOrder                    integer, assigned on join, stable for the session.
                               Play is clockwise, so "who plays next" is derived from the
                               current leader's seat plus the number of plays already in the
                               trick. Without a stable seat order that question has no answer,
                               so this field is load-bearing, not bookkeeping.
  role                         FACILITATOR | PARTICIPANT
  connectionStatus             CONNECTED | DISCONNECTED

Hand                           (1:1 per Player per Session)
  the cards dealt to this player
  cards live in a Hand, not in a shared draw pile — there is no draw pile

Card                           (reference data; seeded once, never mutated during play)
  cardId                       UUID
  strideCategory               the existing StrideCategory enum
  rank                         integer 2–14 (Ace = 14); rank order 2 < 3 < … < K < A
                               Rank does double duty: trick-taking order AND a rough
                               frequency/impact/ease ordering of the threat (whitepaper §2.2)
  threatPrompt                 the text the group discusses
                               Aces read "You've invented a new [Suit] attack."

  Card schema is exactly suit + rank + threat text. No score value, no difficulty rating,
  no example mitigations. Sourced from whitepaper p6 and cards.yaml. Closes R7.

Trick                          (1:N from Session; ordered)
  trickId                      UUID
  session
  ledSuit                      StrideCategory — the suit of the first card played
  sequence                     integer, 1-based within the session
  winner                       Player (null until trick is resolved)
  plays                        1:N TrickPlay, in play order

TrickPlay                      (one player's card in one trick)
  trickPlayId                  UUID
  trick, player, card
  threatLinked                 boolean — did the player successfully link the threat to the system?
                               A card may be legally played with threatLinked = false (scores 0,
                               does not block the trick)
  components                   List<String> — free text, plural; one card may threaten several
                               components. Sourced from Score Card column "Component(s)".
  notes                        optional text
  playedAt

GameState                      (within a Session)
  currentLeader                which Player leads the current trick
  currentTrick                 the Trick in progress (null between tricks)
  completedTricks              1:N Trick
```

`Card` is reference data and is never mutated by gameplay. Everything else is session-scoped
and is discarded when retention is eventually defined — see R6.

The table is named `game_session` rather than `session`, because `session` collides with
HTTP-session vocabulary and with reserved or near-reserved identifiers in several databases.

### Future capability — issue-tracker references

The whitepaper records that after the game "the scorekeeper should create bugs, one bug per
threat identified, in whatever system a development team uses to track bugs." Earliest prototype
cards had space for a bug number. This project already has Jira integration. Recording an
issue-tracker reference against a `TrickPlay` is a natural future capability — **explicitly out
of scope now**, but the schema should not make it impossible to add later.

---

## 6. Open risks

| # | Risk | Owner | Blocks |
|---|------|-------|--------|
| R1 | ~~**The deck's licence is unverified.**~~ **CLOSED.** © 2009 Microsoft Corporation, Creative Commons Attribution 3.0 United States (CC-BY-3.0 US). Confirmed independently in the instruction card copyright page, whitepaper §2 footnote 6, and Shostack's repository README. Attribution to Microsoft is a licence obligation and must be visible in the UI — it is an acceptance criterion on EOP-13. | — | Closed |
| R2 | **No AWS account exists.** `terraform apply` has never run. Nothing is demonstrable at a public IP. | Owner | EOP-7 only. |
| R3 | **Real-time transport undecided.** Server-sent events, WebSocket, or polling. | Tech Lead | EOP-10, EOP-14 |
| R4 | **Player identity undecided.** Even anonymous multiplayer needs a durable credential answering "am I that player?" across a refresh. | Tech Lead | EOP-10, EOP-14 |
| R5 | **Concurrency control undecided.** Two players acting at once must not produce an illegal game state (e.g. two players both believing they hold the same card). Optimistic locking is the likely answer; the semantics are not decided. | Tech Lead | EOP-14 |
| R6 | **Session lifecycle and storage growth undefined.** Sessions accumulate forever on a t3.small with a 10 GB data volume. No expiry, archival, or deletion policy exists. | Owner + Tech Lead | Production readiness |
| R7 | ~~**Card fields beyond suit, number and prompt are unconfirmed.**~~ **CLOSED.** Whitepaper p6: "Each playing card shows a suit, a number, and a threat of the type exemplified by the suit." Schema is exactly suit + rank + threat text. Safe to write the first Liquibase changeset for EOP-6. | — | Closed |
| R8 | ~~**Round mechanics unconfirmed.**~~ **CLOSED.** Trick-taking mechanic fully sourced — see §3.3 and §3.4. | — | Closed |
| R9 | ~~**Whether the modelled system belongs in the app is unconfirmed.**~~ **CLOSED.** Whitepaper §2.1: "an architectural diagram of that system should be available. A whiteboard diagram is ideal." The diagram is a precondition held outside the app. [ASSUMPTION B] confirmed by source. | — | Closed |

---

## 7. Assumptions

These are assumptions, not decisions. Each one is a guess that has not been confirmed by the
owner, and each is cheap to correct now and expensive to correct after EOP-14 is built.
Assumptions confirmed by primary source research are marked **[CONFIRMED]**.

**[ASSUMPTION A] — CONFIRMED AND REPLACED.** The original assumption (draw from a shared pile,
no scoring) was wrong. The game is a trick-taking game. See §3.3 and §3.4, sourced from
`EoP_Instructions.pdf` and `eop_whitepaper.pdf`.

**[ASSUMPTION B] — CONFIRMED.** The system model is not in the application. Players use their
own diagram. The only system-model data stored is the free-text component name(s) entered when a
card is played. Sourced from whitepaper §2.1.

**[ASSUMPTION C] — CORRECTED.** Player count is **3–6, with 3–5 as the tested range.** Two
players is a rejected design point, not merely untested: "two player games often require very
different mechanics from multi-player games" (whitepaper). Six is considered risky. An early
adopter who put ~20 players across 4 teams reported it failed to hold attention.

**[ASSUMPTION D] — CONFIRMED AND STRENGTHENED.** Rank is not merely an identifier. Whitepaper
§2.2: "We wanted a mechanic that associated 'better' threats with higher cards, as higher cards
are more likely to take a trick… We ended up collapsing a combination of our perceived frequency
of encounter, impact and ease of exploiting the threat into an imprecisely ordered list." Rank
does double duty: trick-taking order *and* a rough frequency/impact/ease ordering. Rank order is
2 < 3 < 4 < 5 < 6 < 7 < 8 < 9 < 10 < J < Q < K < A.

**[ASSUMPTION E] — SUPERSEDED.** Turn order is not round-robin. The holder of the lowest-ranked
Tampering card leads the first trick. Subsequent tricks are led by the winner of the previous
trick. See §3.3.

**[ASSUMPTION F] — OPEN.** A reverse proxy serves the built front-end assets and proxies the
API. Caddy is the likely choice, because it obtains and renews TLS certificates automatically,
which turns the accepted no-TLS limitation into a one-line change the day a domain exists. No
ADR selects a reverse proxy today — decision 017 is required before EOP-9 starts.

> **Correction on the record:** no ADR selects a reverse proxy today. ADR-009 chooses React,
> TypeScript, Vite and GOV.UK Frontend and says nothing about a proxy; the only mention anywhere
> in the ADRs is an aside in ADR-012. The topology therefore needs its own decision record
> (number 016) before EOP-9 starts. An earlier draft of the backlog cited "ADR-009 (Caddy, not
> nginx)" as a hard constraint. That was false, and it is recorded here so nobody repeats it.

### Optional variants — known and explicitly declined

The instruction card lists four optional variants. The owner has decided these should be recorded
as known-and-declined rather than silently omitted:

1. **Pass cards after the third trick** — helps a player holding cards they cannot tie to the system.
2. **Double all points; one point for threats on other people's cards.**
3. **Riffing** — other players may add threats, earning one point per additional threat, limited
   to 60 seconds. Moved to optional because "it was exciting to threat modeling experts, and
   difficult or baffling to newcomers."
4. **Mark up the diagram** with where the threat occurs.

All four are out of scope for this PoC.

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
| 7 | [EOP-13](https://maglez.atlassian.net/browse/EOP-13) Replace the placeholder deck with the real content | S | No longer licence-blocked (R1 closed); blocked only on EOP-6 |
| 8 | [EOP-14](https://maglez.atlassian.net/browse/EOP-14) Trick mechanics: deal, lead, follow suit, resolve trick, pass lead | XL | EOP-10, EOP-11, decision 018 |
| 9 | [EOP-15](https://maglez.atlassian.net/browse/EOP-15) Scoring: compute and display points per trick and per game | M | EOP-14 |

**EOP-12 has been retired.** The original "play a card: core game loop" story described a
draw-pile mechanic that does not match the game. It is replaced by EOP-14 (trick mechanics) and
EOP-15 (scoring), which together cover the same ground correctly.

**EOP-13 is no longer licence-blocked.** R1 is closed. EOP-13 is now blocked only on EOP-6
(the card catalogue must exist before the real content can replace the placeholder).

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
| 017 | Concurrency control | EOP-14 |

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
mid-trick. A player who reconnects mid-trick must see the current trick state — led suit, cards
already played, whose turn it is — not a blank board.

---

## 11. Sources

Primary sources consulted for the rules correction on 2026-08-04. Claims in §3.3, §3.4, §5 and
§7 are attributed to these documents.

| Document | Location | Used for |
|----------|----------|---------|
| *Elevation of Privilege* instruction card | `docs/EoP_Microsoft_Docs/EoP_Instructions.pdf` | Dealing, trick-taking rules, opening lead, scoring rule, optional variants, licence copyright page |
| Adam Shostack, "Elevation of Privilege: Drawing Developers into Threat Modeling" (whitepaper) | `docs/EoP_Microsoft_Docs/eop_whitepaper.pdf` | Player count rationale, rank design intent, scoring history (v0.21 abandoned system), system-model precondition, online-version decision, licence footnote 6 |
| Official Score Card | `docs/EoP_Microsoft_Docs/EoP_Score Card.pdf` | Score Card column names (Name, Points, Card, Component(s), Notes on Threat) |
| `cards.yaml` | `https://github.com/adamshostack/eop` | Card count (78 = 6 × 13), suit completeness, licence in README |

**Licence:** © 2009 Microsoft Corporation. Licensed under Creative Commons Attribution 3.0
United States. `http://creativecommons.org/licenses/by/3.0/us/`. Attribution to Microsoft is a
licence obligation and must be visible in the deployed UI — it is an acceptance criterion on
EOP-13.

---

## Related

- [EOP-5](https://maglez.atlassian.net/browse/EOP-5) — the epic this document describes
- `docs/adr/README.md` — the indexed architecture decisions, with implementation status
- `docs/adr/ADR-009-frontend-react-typescript.md` — front-end stack
- `docs/adr/ADR-012-deployment-target.md` — deployment target, and why there is no TLS
- `docs/adr/ADR-004-api-contract-first.md` — the contract-first obligation EOP-6 discharges
- `docs/adr/ADR-005-error-handling.md` — the RFC 9457 obligation EOP-6 discharges
