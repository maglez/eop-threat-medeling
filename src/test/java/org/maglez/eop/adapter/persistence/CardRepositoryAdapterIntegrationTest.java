package org.maglez.eop.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.Card;
import org.maglez.eop.entity.Rank;
import org.maglez.eop.entity.StrideCategory;
import org.maglez.eop.usecase.PageQuery;
import org.maglez.eop.usecase.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the migrations actually apply and that the entity matches the schema they
 * created.
 *
 * <p>This is not incidental coverage. Hibernate runs with {@code ddl-auto=validate},
 * so if a changeset and the entity disagree on a single column the context fails
 * to start — which makes booting the context the assertion that matters most here.
 */
@SpringBootTest
@DisplayName("Card persistence")
class CardRepositoryAdapterIntegrationTest {

    private static final int DECK_SIZE = 78;
    private static final int CARDS_PER_SUIT = 13;

    /** The largest page the contract allows, so one request returns the whole deck. */
    private static final PageQuery WHOLE_DECK = new PageQuery(0, PageQuery.MAX_SIZE);

    @Autowired
    private CardRepositoryAdapter adapter;

    @Autowired
    private CardJpaRepository jpaRepository;

    /**
     * Counts per suit rather than only the total, because a total of 78 is also
     * reachable with an uneven distribution — twelve of one suit and fourteen of
     * another would pass a total-only assertion and produce a deck nobody can
     * play a fair trick with.
     */
    @Test
    @DisplayName("the deck holds thirteen cards in every suit, not merely seventy-eight in total")
    void shouldSeedThirteenCardsPerSuit() {
        final PageResult<Card> page = adapter.findAll(WHOLE_DECK);

        assertThat(page.totalElements()).isEqualTo(DECK_SIZE);

        final Map<StrideCategory, Long> bySuit = page.content().stream()
                .collect(Collectors.groupingBy(Card::suit, Collectors.counting()));

        assertThat(bySuit).hasSize(StrideCategory.values().length);
        assertThat(bySuit).allSatisfy((suit, count) -> assertThat(count).isEqualTo(CARDS_PER_SUIT));
    }

    @Test
    @DisplayName("every suit holds each rank exactly once, so there are no gaps or duplicates")
    void shouldSeedEveryRankOnceInEverySuit() {
        final Map<StrideCategory, List<Rank>> ranksBySuit = adapter.findAll(WHOLE_DECK).content().stream()
                .collect(Collectors.groupingBy(Card::suit, Collectors.mapping(Card::rank, Collectors.toList())));

        assertThat(ranksBySuit).allSatisfy(
                (suit, ranks) -> assertThat(ranks).containsExactlyInAnyOrder(Rank.values()));
    }

    @Test
    @DisplayName("cards come back in STRIDE acronym order, which alphabetical suit sorting would not give")
    void shouldReturnCardsInDeckOrder() {
        final List<Card> deck = adapter.findAll(WHOLE_DECK).content();

        assertThat(deck.getFirst().suit()).isEqualTo(StrideCategory.SPOOFING);
        assertThat(deck.getLast().suit()).isEqualTo(StrideCategory.ELEVATION_OF_PRIVILEGE);
        assertThat(deck.stream().map(Card::suit).distinct().toList())
                .containsExactly(StrideCategory.values());
    }

    @Test
    @DisplayName("within a suit, cards ascend by rank — the ordering trick resolution depends on")
    void shouldOrderBySuitThenAscendingRank() {
        final List<Card> spoofing = adapter.findAll(WHOLE_DECK).content().stream()
                .filter(card -> card.suit() == StrideCategory.SPOOFING)
                .toList();

        assertThat(spoofing).extracting(Card::rank).containsExactly(Rank.values());
    }

    @Test
    @DisplayName("the persisted suit ordering has not drifted from the enum")
    void shouldKeepPersistedSuitOrderInStepWithTheEnum() {
        final List<CardJpaEntity> rows = jpaRepository.findAll();

        assertThat(rows).hasSize(DECK_SIZE);
        assertThat(rows).allSatisfy(row -> assertThat(row.suitOrder()).isEqualTo(row.suit().deckOrder()));
    }

    @Test
    @DisplayName("pages are real: a page size of two yields thirty-nine pages over the deck")
    void shouldPaginateAtTheDatabase() {
        final PageResult<Card> firstPage = adapter.findAll(new PageQuery(0, 2));

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalPages()).isEqualTo(DECK_SIZE / 2);
        assertThat(firstPage.content())
                .extracting(Card::rank)
                .containsExactly(Rank.TWO, Rank.THREE);
        assertThat(adapter.findAll(new PageQuery(DECK_SIZE / 2, 2)).content()).isEmpty();
    }

    @Test
    @DisplayName("a seeded card round-trips through the domain type intact")
    void shouldReadASeededCardById() {
        // Elevation of Privilege, Ace: both the trump suit and an open threat card.
        final UUID trumpAce = UUID.fromString("2a497b0e-e59d-50c9-a24b-f03f347dd4ed");

        final Optional<Card> found = adapter.findById(trumpAce);

        assertThat(found).isPresent();
        assertThat(found.get().suit()).isEqualTo(StrideCategory.ELEVATION_OF_PRIVILEGE);
        assertThat(found.get().rank()).isEqualTo(Rank.ACE);
        assertThat(found.get().isTrump()).isTrue();
        assertThat(found.get().isOpenThreat()).isTrue();
        assertThat(found.get().threatPrompt()).isEqualTo("You've invented a new Elevation of Privilege attack");
    }

    /**
     * Aces carry distinct behaviour in the game — the player must name a threat not
     * printed on any other card — so the seeded text has to say so rather than read
     * like an ordinary high card.
     */
    @Test
    @DisplayName("every ace is an open threat card naming its own suit")
    void shouldSeedEveryAceAsAnOpenThreat() {
        final List<Card> aces = adapter.findAll(WHOLE_DECK).content().stream()
                .filter(Card::isOpenThreat)
                .toList();

        assertThat(aces).hasSize(StrideCategory.values().length);
        assertThat(aces).allSatisfy(ace -> assertThat(ace.threatPrompt()).startsWith("You've invented a new "));
    }

    @Test
    @DisplayName("no placeholder card from the first migration survives")
    void shouldNotRetainAnyPlaceholderCard() {
        final List<UUID> placeholders = List.of(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("66666666-6666-4666-8666-666666666666"));

        assertThat(placeholders).allSatisfy(id -> assertThat(adapter.findById(id)).isEmpty());
    }

    @Test
    @DisplayName("threat prompts are unique, so no card duplicates another card's text")
    void shouldSeedDistinctThreatPrompts() {
        final List<Card> deck = adapter.findAll(WHOLE_DECK).content();

        assertThat(deck.stream().collect(Collectors.toMap(Card::threatPrompt, Function.identity(), (a, b) -> a)))
                .hasSize(DECK_SIZE);
    }

    @Test
    @DisplayName("an unknown identifier is absent, not an error, at this layer")
    void shouldReturnEmptyForAnUnknownIdentifier() {
        assertThat(adapter.findById(UUID.fromString("99999999-9999-4999-8999-999999999999"))).isEmpty();
    }
}
