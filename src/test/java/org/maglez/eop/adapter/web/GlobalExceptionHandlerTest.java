package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.CardNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The error handling rules require a test for every mapped exception, because
 * this class is a single point of failure: a bug here hides every error the API
 * would otherwise report.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("an unknown card is a 404 naming the identifier")
    void shouldMapCardNotFoundTo404() {
        final UUID missing = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

        final ProblemDetail problem = handler.handleCardNotFound(new CardNotFoundException(missing));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Card not found");
        assertThat(problem.getDetail()).contains(missing.toString());
    }

    @Test
    @DisplayName("a rejected argument is a 400 carrying the guard clause's own message")
    void shouldMapIllegalArgumentTo400() {
        final ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("size must be at most 100, was 500"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getDetail()).isEqualTo("size must be at most 100, was 500");
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that reveals nothing about the inside of the system")
    void shouldMapUnexpectedTo500WithoutLeakingDetail() {
        final ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("jdbc:postgresql://10.20.1.7:5432/eop refused"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getDetail()).isEqualTo("The request could not be completed.");
        assertThat(problem.getDetail()).doesNotContain("postgresql");
    }
}
