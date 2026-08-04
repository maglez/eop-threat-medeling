package org.maglez.eop.entity;

import java.util.Objects;
import java.util.UUID;

/**
 * A single threat card from the Elevation of Privilege deck.
 *
 * <p>Immutable reference data. Cards are seeded by a database migration and are
 * never created or changed through the API, so there is deliberately no setter,
 * no builder in production code and no write endpoint.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations. The
 * persistence adapter holds its own separate mapped type.
 *
 * @param cardId       stable identifier, assigned by the seeding migration
 * @param suit         the STRIDE category, the card's suit in the physical game
 * @param rank         the rank printed on the card
 * @param threatPrompt the threat described on the card face
 */
public record Card(UUID cardId, StrideCategory suit, Rank rank, String threatPrompt) {

    /** Longest threat prompt the deck permits, matching the column width. */
    public static final int MAX_THREAT_PROMPT_LENGTH = 500;

    /**
     * Rejects a malformed card at construction rather than letting it travel.
     *
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if the threat prompt is blank or too long
     */
    public Card {
        Objects.requireNonNull(cardId, "cardId is required");
        Objects.requireNonNull(suit, "suit is required");
        Objects.requireNonNull(rank, "rank is required");
        Objects.requireNonNull(threatPrompt, "threatPrompt is required");
        if (threatPrompt.isBlank()) {
            throw new IllegalArgumentException("threatPrompt must not be blank");
        }
        if (threatPrompt.length() > MAX_THREAT_PROMPT_LENGTH) {
            throw new IllegalArgumentException(
                    "threatPrompt must be at most " + MAX_THREAT_PROMPT_LENGTH + " characters, was " + threatPrompt.length());
        }
    }

    /**
     * Whether this card is an Open Threat card. Playing one obliges the player
     * to name a threat that appears on no other card in the deck.
     *
     * @return true if the card is an ace
     */
    public boolean isOpenThreat() {
        return rank == Rank.ACE;
    }

    /**
     * Whether this card belongs to the trump suit. Only a trump card, or a card
     * of the suit that was led, can take a trick.
     *
     * @return true if the card's suit is elevation of privilege
     */
    public boolean isTrump() {
        return suit == StrideCategory.ELEVATION_OF_PRIVILEGE;
    }
}
