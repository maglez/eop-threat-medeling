# ADR-041: The Printed Deck Has No Aces — Deck Size Is 68

**Status:** Accepted  
**Date:** 2026-08-18  
**Deciders:** @tech-lead, @architecture-guardian  
**Story:** EOP-75

---

## Context

The Elevation of Privilege card game was seeded in `002-real-deck.xml` with 78 cards covering
all six STRIDE suits at ranks 2–Ace (14). The trim migration (`2026-08-17--trim-deck-to-74-printed-cards.xml`,
EOP-69) removed four cards absent from the printed deck, leaving 74 cards. This ADR records the
decision to remove the remaining six Ace cards (one per suit), reducing the deck to 68 cards.

### Conflicting sources

Two sources in this repository appeared to disagree:

1. **`docs/EoP_Microsoft_Docs/LICENCE-eop-deck.md:51–64`** quotes the deck's own author:
   > "There are 74, not 78 cards because the Elevation of Privilege suit starts at 5, and
   > Tampering starts at 3…"
   This explanation accounts for exactly four absent cards (Tampering 2, EoP 2, EoP 3, EoP 4)
   and therefore implies an Ace in every suit — consistent with a 74-card deck.

2. **`docs/requirements/PRD-eop-card-game.md:127`** documents the Ace/Open-Threat game rule:
   > "**Aces — Open Threat cards:** each Ace reads 'You've invented a new [Suit] attack.'"
   This rule was implemented as `Card.isOpenThreat()` and was present in the codebase.

### Authoritative evidence

`LICENCE-eop-deck.md:3` states that `eop_whitepaper.pdf` is the authoritative source where
sources disagree. The whitepaper is not committed to this repository, but the card artwork
extracted from the official `EoP_Card Game Images.pdf` is committed under `ui/src/assets/cards/`.

Counting those images:

```
find ui/src/assets/cards -type f -name '*.png' | wc -l   →  68
```

The per-suit breakdown of the committed images:

| Suit                  | Cards | Ranks    |
|-----------------------|-------|----------|
| Spoofing              | 12    | 2–K      |
| Tampering             | 11    | 3–K      |
| Repudiation           | 12    | 2–K      |
| Information Disclosure| 12    | 2–K      |
| Denial of Service     | 12    | 2–K      |
| Elevation of Privilege|  9    | 5–K      |
| **Total**             | **68**|          |

No Ace image exists in any suit. The artwork is the most direct physical evidence of what the
printed deck contains, and it is unambiguous: **the printed deck has 68 cards and no Aces**.

The author's "74 cards" quote in `LICENCE-eop-deck.md` was written before the four EoP/Tampering
low-rank cards were removed from the digital seed, and it describes the deck as the author
understood it at the time of writing. The committed card images supersede that prose description
as the authoritative count.

The PRD's Ace/Open-Threat rule (`PRD-eop-card-game.md:127`) was derived from the 78-card seed
and does not reflect the physical game. It is withdrawn by this ADR.

---

## Decision

**The printed deck has 68 cards. There are no Ace cards. King (rank 13) is the highest rank.**

- `Rank.ACE` (value 14) is removed from the `Rank` enum.
- `Card.isOpenThreat()` is removed — the concept of an Open Threat card does not exist in the
  physical game.
- The Liquibase migration `2026-08-18--remove-ace-cards.xml` deletes the six Ace rows from the
  `card` table.
- The `Rank` enum now has 12 values: `TWO(2)` through `KING(13)`.
- All documentation, API contracts, and tests are updated to reflect 68 cards and King-high.

---

## Consequences

### Positive

- The application's deck now matches the physical printed game exactly.
- The `Rank` enum is simpler and has no dead value.
- `Card.isOpenThreat()` — a method that could never return `true` after this change — is removed
  rather than left as dead code.

### Negative / Constraints

1. **Rollback requires a coordinated application downgrade.** The rollback of
   `2026-08-18--remove-ace-cards.xml` re-inserts rows with `card_rank = 14`. The application
   code deployed alongside this migration maps every row through `Rank.ofValue(cardRank)`, and
   `Rank.ofValue(14)` now throws `IllegalArgumentException`. Rolling back the migration without
   also rolling back the application binary will cause every deck read to fail. Any operator
   performing a rollback must deploy a version of the application that still contains `Rank.ACE`
   before or simultaneously with the database rollback.

2. **ADR-023 deal arithmetic must be re-derived.** The deck constant `D` changes from 74 to 68.
   See the amendment block on ADR-023 below.

3. **PRD-eop-card-game.md references to Aces are withdrawn.** Lines 127, 181, 189–190, 245 and
   528 of the PRD describe Ace cards and the Open-Threat rule. Those descriptions are superseded
   by this ADR and are updated in the PRD document.

4. **The OpenAPI `Rank` enum is a breaking contract change.** Removing `ACE` from the published
   enum is a breaking change for any client that was code-generated from the previous spec. The
   server never produced `ACE` after this migration, so any such client would have received
   responses it could not parse regardless. The spec is updated in step with the code.

---

## Amendment to ADR-023 — Deal Arithmetic with D = 68

*Added 2026-08-18, EOP-75.*

The deck constant `D` changes from 74 to 68. The deal arithmetic in ADR-023 Decision §1 is
re-derived below for the new value.

`floor(D / n)` cards per seat, `D mod n` remainder discarded:

| Players (n) | Cards per seat | Remainder discarded |
|-------------|---------------|---------------------|
| 3           | 22            | 2                   |
| 4           | 17            | 0                   |
| 5           | 13            | 3                   |
| 6           | 11            | 2                   |

The opening-lead rule (lowest Tampering card guaranteed dealt) is unchanged. The lowest Tampering
card is the 3 of Tampering (rank 3), since the 2 of Tampering does not exist in the printed deck.
