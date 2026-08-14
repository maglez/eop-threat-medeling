package org.maglez.eop.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.adapter.web.TrickController;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.PlayCardUseCase;
import org.maglez.eop.usecase.ReadOwnHandUseCase;
import org.maglez.eop.usecase.ResolveTrickUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Asserts that {@code eop.features.trick-play} produces the trick play beans when it is on.
 *
 * <p>{@link TrickPlayDisabledIntegrationTest} is the half of this pair that matters in production,
 * because the flag ships off and an incident is answered by knowing what the off position withholds.
 * On its own, though, that test passes for the wrong reason if the beans are never registered at
 * all: a bean that a typo in the property name has made unreachable is absent with the flag off and
 * absent with the flag on, and only the on position can tell those two apart.
 *
 * <p>There is deliberately no {@code properties} attribute here. The suite already runs with every
 * flag on, so this class reuses the default Spring context rather than asking for a second one; a
 * property override that merely restates the suite default would pay for a whole extra context to
 * assert what the default context could have answered for free.
 *
 * <p>The assertion is on the bean rather than on behaviour, which is what keeps this test honest
 * about its subject: the wiring is what is under test, and the four use cases have their own unit
 * tests for what they do once wired.
 *
 * <p>The controller is asserted here too, and it is the bean this pair most needs to cover. The
 * four use cases are only reachable by something that injects them, so with the flag off they are
 * unreachable code; the controller is the thing that makes them reachable over HTTP, and a flag
 * that withheld the use cases but left the routes mapped would fail at request time with a missing
 * dependency rather than with the 404 the contract promises.
 */
@SpringBootTest
@DisplayName("Trick play with the feature flag on")
class TrickPlayEnabledIntegrationTest {

    /** The context, asked directly, because the question is which beans exist. */
    @Autowired private ApplicationContext context;

    /** Asserts the deal use case is registered while the flag is on. */
    @Test
    @DisplayName("creates the deal hands use case")
    void shouldRegisterTheDealHandsUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(DealHandsUseCase.class))
                .as("the flag is on for the suite, so the deal must be wired")
                .isNotEmpty();
    }

    /** Asserts the play use case is registered while the flag is on. */
    @Test
    @DisplayName("creates the play card use case")
    void shouldRegisterThePlayCardUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(PlayCardUseCase.class))
                .as("the flag is on for the suite, so playing a card must be wired")
                .isNotEmpty();
    }

    /** Asserts the resolve use case is registered while the flag is on. */
    @Test
    @DisplayName("creates the resolve trick use case")
    void shouldRegisterTheResolveTrickUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(ResolveTrickUseCase.class))
                .as("the flag is on for the suite, so resolving a trick must be wired")
                .isNotEmpty();
    }

    /** Asserts the own-hand read use case is registered while the flag is on. */
    @Test
    @DisplayName("creates the read own hand use case")
    void shouldRegisterTheReadOwnHandUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(ReadOwnHandUseCase.class))
                .as("reading a hand is gated with the writers: with the flag off there are no hands to read")
                .isNotEmpty();
    }

    /** Asserts the controller carrying the four routes is registered while the flag is on. */
    @Test
    @DisplayName("creates the trick controller, which is what makes the use cases reachable")
    void shouldRegisterTheTrickController() {
        Assertions.assertThat(context.getBeanNamesForType(TrickController.class))
                .as("the routes and the use cases are behind one flag, so both must appear together")
                .isNotEmpty();
    }
}
