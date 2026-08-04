package org.maglez.eop.usecase;

import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.Card;

/**
 * Port through which the application reads the threat card deck.
 *
 * <p>Declared here, in the layer that needs it, and implemented outward in the
 * persistence adapter. That is what keeps the dependency arrow pointing inwards:
 * the database depends on the application, not the reverse.
 *
 * <p>Read only by design. The deck is reference data seeded by migration, so
 * there is no save, no delete and no write endpoint anywhere above this port.
 */
public interface CardRepository {

    /**
     * Reads one page of the deck in canonical order: by suit, then ascending rank.
     *
     * @param query which page to read
     * @return the requested page, empty content if the page is past the end
     */
    PageResult<Card> findAll(PageQuery query);

    /**
     * Reads a single card.
     *
     * @param cardId the identifier to look up
     * @return the card, or empty if no card has that identifier
     */
    Optional<Card> findById(UUID cardId);
}
