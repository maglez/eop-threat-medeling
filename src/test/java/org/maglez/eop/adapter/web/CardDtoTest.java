package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.maglez.eop.entity.CardBuilder.aCard;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.Card;
import org.maglez.eop.entity.Rank;
import org.maglez.eop.entity.StrideCategory;
import org.maglez.eop.usecase.PageResult;

@DisplayName("Web transport objects")
class CardDtoTest {

    @Test
    @DisplayName("publishes the rank three ways: name to switch on, symbol to print, value to compare")
    void shouldPublishRankThreeWays() {
        final UUID cardId = UUID.fromString("00000000-0000-4000-8000-000000000123");
        final Card card = aCard()
                .withCardId(cardId)
                .withSuit(StrideCategory.ELEVATION_OF_PRIVILEGE)
                .withRank(Rank.JACK)
                .withThreatPrompt("An attacker could gain rights never granted.")
                .build();

        final CardDto dto = CardDto.from(card);

        assertThat(dto.cardId()).isEqualTo(cardId.toString());
        assertThat(dto.suit()).isEqualTo("ELEVATION_OF_PRIVILEGE");
        assertThat(dto.rank()).isEqualTo("JACK");
        assertThat(dto.rankSymbol()).isEqualTo("J");
        assertThat(dto.rankValue()).isEqualTo(11);
        assertThat(dto.threatPrompt()).isEqualTo("An attacker could gain rights never granted.");
    }

    @Test
    @DisplayName("the paged envelope carries the page count so a client need not compute it")
    void shouldCarryTotalPages() {
        final PagedResponse<String> response = PagedResponse.from(new PageResult<>(List.of("a"), 2, 4, 10));

        assertThat(response.content()).containsExactly("a");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(4);
        assertThat(response.totalElements()).isEqualTo(10);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
