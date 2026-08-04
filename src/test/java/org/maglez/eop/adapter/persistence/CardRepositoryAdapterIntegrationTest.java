package org.maglez.eop.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * Proves the first migration in the project actually applies and that the entity
 * matches the schema it created.
 *
 * <p>This is not incidental coverage. Hibernate runs with {@code ddl-auto=validate},
 * so if the changeset and the entity disagree on a single column the context fails
 * to start — which makes booting the context the assertion that matters most here.
 */
@SpringBootTest
@DisplayName("Card persistence")
class CardRepositoryAdapterIntegrationTest {

    private static final int PLACEHOLDER_DECK_SIZE = 6;

    @Autowired
    private CardRepositoryAdapter adapter;

    @Autowired
    private CardJpaRepository jpaRepository;

    @Test
    @DisplayName("the migration seeds one placeholder card per STRIDE category")
    void shouldSeedOneCardPerCategory() {
        final PageResult<Card> page = adapter.findAll(PageQuery.firstPage());

        assertThat(page.totalElements()).isEqualTo(PLACEHOLDER_DECK_SIZE);
        assertThat(page.content()).extracting(Card::suit).containsExactlyInAnyOrder(StrideCategory.values());
    }

    @Test
    @DisplayName("cards come back in STRIDE acronym order, which alphabetical suit sorting would not give")
    void shouldReturnCardsInDeckOrder() {
        final PageResult<Card> page = adapter.findAll(PageQuery.firstPage());

        assertThat(page.content()).extracting(Card::suit).containsExactly(StrideCategory.values());
    }

    @Test
    @DisplayName("the persisted suit ordering has not drifted from the enum")
    void shouldKeepPersistedSuitOrderInStepWithTheEnum() {
        final List<CardJpaEntity> rows = jpaRepository.findAll();

        assertThat(rows).hasSize(PLACEHOLDER_DECK_SIZE);
        assertThat(rows).allSatisfy(row -> assertThat(row.suitOrder()).isEqualTo(row.suit().deckOrder()));
    }

    @Test
    @DisplayName("pages are real: a page size of two yields three pages over six cards")
    void shouldPaginateAtTheDatabase() {
        final PageResult<Card> firstPage = adapter.findAll(new PageQuery(0, 2));
        final PageResult<Card> lastPage = adapter.findAll(new PageQuery(2, 2));

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(lastPage.content())
                .extracting(Card::suit)
                .containsExactly(StrideCategory.DENIAL_OF_SERVICE, StrideCategory.ELEVATION_OF_PRIVILEGE);
        assertThat(adapter.findAll(new PageQuery(3, 2)).content()).isEmpty();
    }

    @Test
    @DisplayName("a seeded card round-trips through the domain type intact")
    void shouldReadASeededCardById() {
        final UUID trumpCard = UUID.fromString("66666666-6666-4666-8666-666666666666");

        final Optional<Card> found = adapter.findById(trumpCard);

        assertThat(found).isPresent();
        assertThat(found.get().suit()).isEqualTo(StrideCategory.ELEVATION_OF_PRIVILEGE);
        assertThat(found.get().rank()).isEqualTo(Rank.KING);
        assertThat(found.get().isTrump()).isTrue();
        assertThat(found.get().threatPrompt()).isNotBlank();
    }

    @Test
    @DisplayName("an unknown identifier is absent, not an error, at this layer")
    void shouldReturnEmptyForAnUnknownIdentifier() {
        assertThat(adapter.findById(UUID.fromString("99999999-9999-4999-8999-999999999999"))).isEmpty();
    }
}
