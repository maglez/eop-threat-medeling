package org.maglez.eop.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.maglez.eop.entity.CardBuilder.aCard;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.Card;
import org.maglez.eop.entity.CardNotFoundException;

@DisplayName("GetCardUseCase")
class GetCardUseCaseTest {

    private static final UUID KNOWN = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final UUID UNKNOWN = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

    private final Card known = aCard().withCardId(KNOWN).build();

    @Test
    @DisplayName("returns the card when it exists")
    void shouldReturnTheCard() {
        final GetCardUseCase useCase = new GetCardUseCase(new InMemoryCardRepository(known));

        assertThat(useCase.execute(KNOWN)).isEqualTo(known);
    }

    @Test
    @DisplayName("turns an absent card into a domain failure, because the caller named a specific card")
    void shouldFailWhenTheCardDoesNotExist() {
        final GetCardUseCase useCase = new GetCardUseCase(new InMemoryCardRepository(known));

        assertThatThrownBy(() -> useCase.execute(UNKNOWN))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessageContaining(UNKNOWN.toString());
    }

    @Test
    @DisplayName("refuses to be built without a repository, and refuses a null identifier")
    void shouldRejectMissingCollaborators() {
        assertThatNullPointerException().isThrownBy(() -> new GetCardUseCase(null)).withMessageContaining("cardRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> new GetCardUseCase(new InMemoryCardRepository()).execute(null))
                .withMessageContaining("cardId");
    }
}
