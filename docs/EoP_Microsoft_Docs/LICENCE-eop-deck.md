# Licence and provenance — Elevation of Privilege deck

The card deck, its threat text, the printed instructions and the whitepaper in this directory are
**not** the work of this project. They are Microsoft's, and they are reused here under licence.

## Copyright and licence

> © 2009 Microsoft Corporation. All rights reserved.
> Licensed under Creative Commons Attribution 3.0 United States.

- Licence: **Creative Commons Attribution 3.0 United States (CC-BY-3.0 US)**
- Licence text: <http://creativecommons.org/licenses/by/3.0/us/>
- Original download: <https://www.microsoft.com/en-us/download/details.aspx?id=20303>

The licence is stated independently in three places, which is why the question is considered
settled rather than assumed:

1. The copyright page of `EoP_Instructions.pdf` — the rules card shipped in the physical box.
2. `eop_whitepaper.pdf`, section 2, footnote 6.
3. The README of <https://github.com/adamshostack/eop>, maintained by the game's author.

## What the licence permits, and what it obliges

CC-BY-3.0 US permits reproduction, distribution and **derivative works**, including commercial
use. The whitepaper goes further than merely permitting it:

> "We encourage readers to play the game and to play with the game, modifying it and customizing
> it."

The single obligation is **attribution**. That is not a formality to be discharged by this file:

> **Attribution to Microsoft must be visible in the running application's user interface**, not
> only in the repository. It is a hard acceptance criterion on the story that seeds the real deck
> (EOP-13), not a nice-to-have.

## Files in this directory

| File | Tracked | What it is |
|---|---|---|
| `EoP_Instructions.pdf` | yes | The 4-page rules card from the box. Authoritative on the rules as shipped. |
| `eop_whitepaper.pdf` | yes | Adam Shostack, *Elevation of Privilege: Drawing Developers into Threat Modeling*. Authoritative on design intent, and decisive wherever sources disagree. |
| `EoP_Score Card.pdf` | yes | The official score sheet. Source of the recorded-artefact columns. |
| `EoP_Card Game Images.pdf` | yes | Printable card artwork, ~6 MB. |
| `EoP_Cards_Box_Native_files.zip` | **no** | Illustrator/InDesign sources, ~86 MB. Deliberately gitignored — nothing here builds from it, and the deck content comes from `cards.yaml`. Re-download from the link above if the artwork is ever needed. |

## Deck content used by this project

The threat text is seeded from `cards.yaml` in <https://github.com/adamshostack/eop>, maintained by
the game's author under the same CC-BY-3.0 US licence.

That file holds **78 cards** — six suits of thirteen ranks. The printed deck held **74**, because
the Elevation of Privilege suit started at 5 and Tampering at 3:

> "There are 74, not 78 cards because the Elevation of Privilege suit starts at 5, and Tampering
> starts at 3 because we didn't have sufficient hints to fill out the suit and wanted to avoid
> repetition."

The whitepaper's Future Work section names filling those gaps as the authors' own intention
("There's a possibility of enhancing the game to contain 13 threats per suit"), so the 78-card
file is the completed version of that intent rather than a corrupted copy of the 74-card deck.
Seeding from it also removes any need for suit-specific minimum-rank special cases in the code.

> **Amendment, 2026-08-17 (EOP-69):** The seeded deck was subsequently trimmed back to the
> 74-card printed deck by migration `2026-08-17--trim-deck-to-74-printed-cards.xml`. The
> simplification above was reversed: the application now seeds exactly the printed deck, and
> suit-specific minimum ranks (Tampering starts at 3, EoP starts at 5) are an explicit property
> of the seeded data rather than an artefact to be avoided. See
> [ADR-023](../adr/ADR-023-deal-remainder-and-turn-order.md) for the full rationale.

> **Amendment, 2026-08-18 (EOP-75):** The six Ace cards (one per suit, rank 14) were subsequently
> removed by migration `2026-08-18--remove-ace-cards.xml`. The card artwork in
> `ui/src/assets/cards/` contains 68 PNG files with no Ace in any suit; the artwork is the
> authoritative source. The author's "74 cards" quote above refers to the deck without the four
> absent low-rank cards (Tampering 2, EoP 2/3/4), not to a deck with Aces. The deck is now 68
> cards: SPOOFING 12 ranks (2–K), TAMPERING 11 ranks (3–K), REPUDIATION 12 ranks (2–K),
> INFORMATION_DISCLOSURE 12 ranks (2–K), DENIAL_OF_SERVICE 12 ranks (2–K),
> ELEVATION_OF_PRIVILEGE 9 ranks (5–K). King is the highest rank at value 13.
> See [ADR-041](../adr/ADR-041-printed-deck-has-no-aces.md) for the full decision.

See `docs/requirements/PRD-eop-card-game.md` §11 for how each rule in the product requirements is
attributed to these sources.
