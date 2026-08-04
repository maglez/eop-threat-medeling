package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.entity.CardBuilder.aCard;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.Card;
import org.maglez.eop.entity.Rank;
import org.maglez.eop.entity.StrideCategory;

@DisplayName("ListCardsUseCase")
class ListCardsUseCaseTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID SECOND = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID THIRD = UUID.fromString("00000000-0000-4000-8000-00000000000c");

    private final Card first = aCard().withCardId(FIRST).withSuit(StrideCategory.SPOOFING).withRank(Rank.THREE).build();
    private final Card second = aCard().withCardId(SECOND).withSuit(StrideCategory.TAMPERING).withRank(Rank.FIVE).build();
    private final Card third = aCard().withCardId(THIRD).withSuit(StrideCategory.REPUDIATION).withRank(Rank.SEVEN).build();

    @Test
    @DisplayName("returns the page the repository produced, unaltered")
    void shouldReturnTheRepositoryPage() {
        final InMemoryCardRepository repository = new InMemoryCardRepository(first, second, third);

        final PageResult<Card> result = new ListCardsUseCase(repository).execute(PageQuery.firstPage());

        assertThat(result.content()).containsExactly(first, second, third);
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("passes the caller's query straight through rather than substituting its own")
    void shouldPassTheQueryThrough() {
        final InMemoryCardRepository repository = new InMemoryCardRepository(first, second, third);
        final PageQuery query = new PageQuery(1, 2);

        final PageResult<Card> result = new ListCardsUseCase(repository).execute(query);

        assertThat(repository.lastQuery()).isEqualTo(query);
        assertThat(result.content()).containsExactly(third);
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty deck is an empty page, not an error")
    void shouldReturnAnEmptyPageForAnEmptyDeck() {
        final PageResult<Card> result = new ListCardsUseCase(new InMemoryCardRepository()).execute(PageQuery.firstPage());

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("refuses to be built without a repository, and refuses a null query")
    void shouldRejectMissingCollaborators() {
        assertThatNullPointerException().isThrownBy(() -> new ListCardsUseCase(null)).withMessageContaining("cardRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> new ListCardsUseCase(new InMemoryCardRepository()).execute(null))
                .withMessageContaining("query");
    }
}
