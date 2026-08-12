package org.maglez.eop.entity;

import java.util.UUID;

/**
 * Raised when a player tries to play a card that is not in their hand.
 *
 * <p>This is the check that stops a card being played twice, and it is the
 * domain half of a defence that has a database half too: the unique constraint
 * on a played card is what settles two simultaneous requests for the same card,
 * because both of them can pass this check against the same starting hand
 * (ADR-020). Neither half is sufficient alone.
 *
 * <p>It is also the check that stops a player playing a card that was dealt to
 * somebody else, so a hand identifier is carried alongside the card.
 */
public class CardNotInHandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID handId;

    private final UUID cardId;

    /**
     * Creates the exception for a card the hand does not hold.
     *
     * @param handId the hand that was asked for the card
     * @param cardId the card that is not in it
     */
    public CardNotInHandException(final UUID handId, final UUID cardId) {
        super("Hand " + handId + " does not hold card " + cardId);
        this.handId = handId;
        this.cardId = cardId;
    }

    /**
     * The hand that was asked for the card.
     *
     * @return the hand identifier
     */
    public UUID handId() {
        return handId;
    }

    /**
     * The card the hand does not hold.
     *
     * @return the card identifier
     */
    public UUID cardId() {
        return cardId;
    }
}
