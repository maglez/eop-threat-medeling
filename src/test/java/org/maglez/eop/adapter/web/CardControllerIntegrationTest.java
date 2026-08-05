package org.maglez.eop.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the endpoints against the committed contract in
 * {@code docs/api/openapi.yml}: the field names, the paged envelope shape, and
 * that failures arrive as RFC 9457 problem details rather than Spring's default
 * error body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GET /api/v1/cards")
class CardControllerIntegrationTest {

    private static final String CARDS = "/api/v1/cards";
    /** Elevation of Privilege, Ace: the trump suit and an open threat card. */
    private static final String TRUMP_ACE_ID = "2a497b0e-e59d-50c9-a24b-f03f347dd4ed";
    private static final int DECK_SIZE = 78;
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("returns the seeded deck in the paged envelope the contract declares")
    void shouldListTheSeededDeck() throws Exception {
        mockMvc.perform(get(CARDS))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content.length()").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(DECK_SIZE))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.content[0].suit").value("SPOOFING"))
                .andExpect(jsonPath("$.content[0].rank").value("TWO"))
                .andExpect(jsonPath("$.content[0].rankSymbol").value("2"))
                .andExpect(jsonPath("$.content[0].rankValue").value(2))
                .andExpect(jsonPath("$.content[0].threatPrompt").isNotEmpty())
                // Thirteen cards per suit, so the first page of twenty crosses one
                // suit boundary exactly: Spoofing 2..A, then Tampering 2..8.
                .andExpect(jsonPath("$.content[12].rank").value("ACE"))
                .andExpect(jsonPath("$.content[13].suit").value("TAMPERING"))
                .andExpect(jsonPath("$.content[19].rankValue").value(8));
    }

    @Test
    @DisplayName("honours the requested page")
    void shouldReturnTheRequestedPage() throws Exception {
        mockMvc.perform(get(CARDS).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(DECK_SIZE / 2))
                .andExpect(jsonPath("$.content[0].suit").value("SPOOFING"))
                .andExpect(jsonPath("$.content[0].rankValue").value(4));
    }

    @Test
    @DisplayName("a page past the end is an empty page, not a 404")
    void shouldReturnAnEmptyPagePastTheEnd() throws Exception {
        mockMvc.perform(get(CARDS).param("page", "99").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(DECK_SIZE));
    }

    @Test
    @DisplayName("the largest allowed page returns the whole deck in one response")
    void shouldReturnTheWholeDeckInOnePage() throws Exception {
        mockMvc.perform(get(CARDS).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(DECK_SIZE))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[77].suit").value("ELEVATION_OF_PRIVILEGE"))
                .andExpect(jsonPath("$.content[77].rank").value("ACE"));
    }

    @Test
    @DisplayName("an out-of-range page size is rejected rather than quietly clamped")
    void shouldRejectAnOversizedPage() throws Exception {
        mockMvc.perform(get(CARDS).param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("size must be at most 100, was 500"));
    }

    @Test
    @DisplayName("returns a single card by identifier")
    void shouldReturnASingleCard() throws Exception {
        mockMvc.perform(get(CARDS + "/" + TRUMP_ACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardId").value(TRUMP_ACE_ID))
                .andExpect(jsonPath("$.suit").value("ELEVATION_OF_PRIVILEGE"))
                .andExpect(jsonPath("$.rankSymbol").value("A"))
                .andExpect(jsonPath("$.threatPrompt")
                        .value("You've invented a new Elevation of Privilege attack"));
    }

    @Test
    @DisplayName("an unknown identifier is a 404 problem detail")
    void shouldReturnProblemDetailForUnknownCard() throws Exception {
        mockMvc.perform(get(CARDS + "/99999999-9999-4999-8999-999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("an identifier that is not a UUID is a 400 problem detail, handled by the framework")
    void shouldRejectAMalformedIdentifier() throws Exception {
        mockMvc.perform(get(CARDS + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    @DisplayName("the deck is read only: there is no way to create a card over HTTP")
    void shouldNotExposeAWriteVerb() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(CARDS))
                .andExpect(status().isMethodNotAllowed());
    }
}
