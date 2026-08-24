# Database Schema

Entity-relationship diagram for the EoP threat-modelling card game, derived directly from the
Liquibase changelogs under `src/main/resources/db/changelog/changes/`. **This file is the
authoritative visual reference for the schema.** The partial ER diagram in `C4-Diagrams.md`
(added by EOP-14 Slice B) covers only the trick-play tables and carries no column detail; this
file supersedes it as the complete picture.

**Freshness rule.** Every Liquibase changeset that creates a table, adds a column, or alters a
constraint must be reflected here in the same commit. The diagram is Mermaid `erDiagram` syntax
so it version-controls and reviews as text.

---

## Full entity-relationship diagram

```mermaid
erDiagram
    card {
        UUID id PK
        VARCHAR_32 suit "STRIDE enum name"
        INT suit_order "1-6, canonical deck order"
        INT card_rank "2-14, numeric rank"
        VARCHAR_500 threat_prompt
    }

    game_session {
        UUID id PK
        VARCHAR_8 join_code UK "Crockford base32, upper-case"
        VARCHAR_16 status "LOBBY | IN_PROGRESS | COMPLETED | ABANDONED"
        TIMESTAMP created_at
        TIMESTAMP updated_at
        BIGINT version "optimistic lock"
        INT current_leader_seat "nullable; unset in LOBBY"
        TIMESTAMP expires_at "NOT NULL; default NOW()+24h"
    }

    player {
        UUID id PK
        UUID game_session_id FK
        VARCHAR_40 display_name
        INT seat_order "0-5"
        VARCHAR_16 player_role "FACILITATOR | PARTICIPANT"
        VARCHAR_16 connection_status "CONNECTED | DISCONNECTED"
        VARCHAR_64 identity_token_hash UK "SHA-256 hex, never plaintext"
        TIMESTAMP joined_at
    }

    hand {
        UUID id PK
        UUID game_session_id FK
        UUID player_id FK
        INT seat_order "mirrors player.seat_order"
    }

    hand_card {
        UUID hand_id PK,FK
        UUID card_id PK,FK
    }

    trick {
        UUID id PK
        UUID game_session_id FK
        INT sequence "1-based, unique per session"
        INT leader_seat "seat that led this trick"
        UUID winner_play_id FK "nullable; SET NULL on delete"
    }

    trick_play {
        UUID id PK
        UUID trick_id FK
        UUID player_id FK
        INT seat_order "mirrors player.seat_order"
        UUID card_id FK
        BOOLEAN threat_linked
        VARCHAR_2000 notes "nullable"
        TIMESTAMP played_at
    }

    trick_play_component {
        UUID trick_play_id PK,FK
        INT ordinal PK "0-19; order is meaningful"
        VARCHAR_200 component_name
    }

    game_result {
        UUID id PK
        UUID game_session_id FK
        VARCHAR_40 facilitator_display_name "denormalised; survives session sweep"
        TIMESTAMP started_at "when deck was first dealt"
        TIMESTAMP finalised_at "when last trick was resolved"
    }

    game_result_player {
        UUID id PK
        UUID game_result_id FK
        UUID player_id "advisory; not a FK — player row may be swept"
        VARCHAR_40 display_name "denormalised; survives session sweep"
        INT seat_order
        INT score
    }

    game_session ||--o{ player          : "seats (CASCADE)"
    game_session ||--o{ hand            : "one per seat (CASCADE)"
    game_session ||--o{ trick           : "sequence 1..n (CASCADE)"
    game_session ||--o{ game_result     : "historical results (CASCADE)"

    player      ||--o{ hand            : "holds, seat-bound (CASCADE)"
    player      ||--o{ trick_play      : "plays, seat-bound (CASCADE)"

    hand        ||--o{ hand_card       : "20 or 19 cards (CASCADE)"
    card        ||--o{ hand_card       : "dealt as (NO ACTION)"

    trick       ||--o{ trick_play      : "one per seat (CASCADE)"
    trick_play  ||--o{ trick_play_component : "0..20 ordered (CASCADE)"
    card        ||--o{ trick_play      : "played as (NO ACTION)"
    trick_play  |o--o| trick           : "wins (SET NULL)"

    game_result ||--o{ game_result_player : "per-player scores (CASCADE)"
```

---

## Tables at a glance

| Table | Rows at runtime | Notes |
|---|---|---|
| `card` | 74 (seeded, immutable) | STRIDE reference deck; never deleted at runtime |
| `game_session` | one per active game | Aggregate root; expires after 24 h |
| `player` | 2–6 per session | No cross-session identity |
| `hand` | one per seated player | Created at deal |
| `hand_card` | 20 or 19 per hand | Composite PK `(hand_id, card_id)` |
| `trick` | one per trick played | 1-based sequence per session |
| `trick_play` | one per seat per trick | Seat-bound to `player` via composite FK |
| `trick_play_component` | 0–20 per play | Ordinal is load-bearing (user-typed order) |
| `game_result` | one per completed game | Survives session sweep |
| `game_result_player` | one per seated player per game | `player_id` is advisory, not a FK |

---

## Key design decisions (summary)

- **Seat binding is structural, not probabilistic.** `fk_hand_player_seat` and
  `fk_trick_play_player_seat` reference `player (id, seat_order)` — a play or hand claiming a
  seat its player does not hold cannot be represented. See ADR-023.

- **`card` rows are never deleted.** Both `fk_hand_card_card` and `fk_trick_play_card` carry
  `NO ACTION` (the default) so a delete that would orphan a dealt or played card fails loudly.

- **`trick.winner_play_id` is `ON DELETE SET NULL`.** The one cycle in the diagram. Deleting a
  winning play clears the winner rather than blocking the cascade. No application path deletes a
  single play — plays go only by cascade. See ADR-023.

- **`game_result_player.player_id` is not a foreign key.** The result must survive session sweep
  (which deletes `player` rows). `display_name` is denormalised for the same reason.

- **Identifiers are UUIDv7, generated in the application.** No column defaults; the application
  always supplies the value. See ADR-018.

- **`join_code` widened to 8 characters** by migration `2026-08-22--widen-join-code-to-8-characters.xml`.
  The column is `VARCHAR(8)` in production; the original `VARCHAR(6)` is historical.
