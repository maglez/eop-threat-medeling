package org.maglez.eop.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.adapter.web.EndSessionController;
import org.maglez.eop.adapter.web.ScoreController;
import org.maglez.eop.adapter.web.TrickController;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.EndSessionUseCase;
import org.maglez.eop.usecase.GetScoreUseCase;
import org.maglez.eop.usecase.GetTrickStateUseCase;
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
 * about its subject: the wiring is what is under test, and the six use cases have their own unit
 * tests for what they do once wired.
 *
 * <p>Both controllers are asserted here too, and they are the beans this pair most needs to cover.
 * The six use cases are only reachable by something that injects them, so with the flag off they are
 * unreachable code; the controllers are the things that make them reachable over HTTP, and a flag
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

    /**
     * Asserts the state-of-play use case is registered while the flag is on.
     *
     * <p>{@link TrickPlayDisabledIntegrationTest} asserts this bean is absent with the flag off.
     * That is the assertion which would pass for the wrong reason on its own: a typo in the property
     * name, or a bean method left off the configuration altogether, is absent in both positions, and
     * only this test tells an intentionally withheld bean from a missing one.
     *
     * <p>It was the newest of EOP-14's five and the one a reader is most likely to forget. EOP-14 Slice E
     * added it, and this pair had asserted four use cases up to that point, so the off position
     * knew about the fifth bean before the on position did.
     */
    @Test
    @DisplayName("creates the trick state use case")
    void shouldRegisterTheGetTrickStateUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(GetTrickStateUseCase.class))
                .as("without this bean no client can learn whose turn it is, so the flag would gate an unplayable game")
                .isNotEmpty();
    }

    /** Asserts the controller carrying the five routes is registered while the flag is on. */
    @Test
    @DisplayName("creates the trick controller, which is what makes the use cases reachable")
    void shouldRegisterTheTrickController() {
        Assertions.assertThat(context.getBeanNamesForType(TrickController.class))
                .as("the routes and the use cases are behind one flag, so both must appear together")
                .isNotEmpty();
    }

    /**
     * Asserts the score use case is registered while the flag is on.
     *
     * <p>The newest of the six, and the one the off position is least able to vouch for on its
     * own: a bean that was never declared is absent in both positions, so only this assertion
     * separates a flag that withholds the score from a score that was never wired at all.
     */
    @Test
    @DisplayName("creates the score use case")
    void shouldRegisterTheGetScoreUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(GetScoreUseCase.class))
                .as("the flag is on for the suite, so the score must be wired")
                .isNotEmpty();
    }

    /**
     * Asserts the score controller is registered while the flag is on.
     *
     * <p>A second controller behind the same flag, carrying the sixth route. It is asserted
     * separately from the use case because the two are withheld by two different conditions that
     * happen to name the same property, and a typo in either would leave the other still wired.
     */
    @Test
    @DisplayName("creates the score controller")
    void shouldRegisterTheScoreController() {
        Assertions.assertThat(context.getBeanNamesForType(ScoreController.class))
                .as("the flag is on for the suite, so the score route must be served")
                .isNotEmpty();
    }

    /**
     * Asserts the end-session use case is registered while the flag is on.
     *
     * <p>The newest of the seven, and the one the off position is least able to vouch for on its
     * own: a bean that was never declared is absent in both positions, so only this assertion
     * separates a flag that withholds the end-session path from an end-session path that was never
     * wired at all.
     */
    @Test
    @DisplayName("creates the end-session use case")
    void shouldRegisterTheEndSessionUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(EndSessionUseCase.class))
                .as("the flag is on for the suite, so the end-session use case must be wired")
                .isNotEmpty();
    }

    /**
     * Asserts the end-session controller is registered while the flag is on.
     *
     * <p>A third controller behind the same flag, carrying the seventh route. Asserted separately
     * from the use case because the two are withheld by two independent conditions, and a typo in
     * either would leave the other still wired.
     */
    @Test
    @DisplayName("creates the end-session controller, which is what makes the /end route reachable")
    void shouldRegisterTheEndSessionController() {
        Assertions.assertThat(context.getBeanNamesForType(EndSessionController.class))
                .as("the routes and the use cases are behind one flag, so both must appear together")
                .isNotEmpty();
    }
}
