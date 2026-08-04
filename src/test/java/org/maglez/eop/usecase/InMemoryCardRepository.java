package org.maglez.eop.usecase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.Card;

/**
 * In-memory stand-in for the card port.
 *
 * <p>Hand written rather than mocked. The use cases under test are thin, so what
 * matters is that they pass the right query through and interpret the right
 * answer; a real list makes that readable, whereas a mock would mostly restate
 * the implementation back at itself.
 */
final class InMemoryCardRepository implements CardRepository {

    private final Map<UUID, Card> cards = new LinkedHashMap<>();

    private PageQuery lastQuery;

    InMemoryCardRepository(final Card... seed) {
        for (final Card card : seed) {
            cards.put(card.cardId(), card);
        }
    }

    @Override
    public PageResult<Card> findAll(final PageQuery query) {
        this.lastQuery = query;
        final List<Card> all = List.copyOf(cards.values());
        final int from = Math.min(query.page() * query.size(), all.size());
        final int to = Math.min(from + query.size(), all.size());
        return new PageResult<>(all.subList(from, to), query.page(), query.size(), all.size());
    }

    @Override
    public Optional<Card> findById(final UUID cardId) {
        return Optional.ofNullable(cards.get(cardId));
    }

    /**
     * The query the use case actually passed down, so a test can prove it was not
     * quietly rewritten on the way through.
     *
     * @return the last query received, or null if none
     */
    PageQuery lastQuery() {
        return lastQuery;
    }
}
