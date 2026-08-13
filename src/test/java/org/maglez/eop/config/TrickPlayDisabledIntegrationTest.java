package org.maglez.eop.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.adapter.security.SecureRandomDeckShuffler;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.PlayCardUseCase;
import org.maglez.eop.usecase.ResolveTrickUseCase;
import org.maglez.eop.usecase.StartSessionUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Asserts that {@code eop.features.trick-play} withholds trick play when it is off.
 *
 * <p>The suite runs with every flag on, because a suite that ran with the feature off would be
 * testing the absence of it. This one class overrides the flag back to false, which costs a second
 * Spring context, and that cost is the price of knowing the flag works: a flag nobody tests in its
 * off position is a flag that will not be trusted in an incident.
 *
 * <p>The assertions are about beans rather than routes, because this slice ships no route. That is
 * also what makes them worth writing. The previous slice put five trick-play tables and two ports
 * into the application, reachable by anything that injects {@code HandRepository} or
 * {@code TrickRepository}, and this slice adds the first three beans that do. Asserting the beans
 * are absent pins the mechanism: {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * withholds the code rather than leaving it in place to branch at request time (ADR-013). With the
 * flag off nothing in the application calls the ports that write a hand, a trick or a play. The
 * adapter implementing those ports is an unconditional {@code @Repository} and is created either
 * way, so what the flag withholds is every caller of it rather than the capability itself.
 *
 * <p>The last test is the counterweight. A flag that took the rest of the application down with it
 * would be worse than no flag, so the shuffler — deliberately ungated, because a stateless
 * permutation reaches no table — and the session lifecycle beans are asserted to be present.
 */
@SpringBootTest(properties = "eop.features.trick-play=false")
@DisplayName("Trick play with the feature flag off")
class TrickPlayDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("does not create the deal-hands use case at all")
    void shouldNotRegisterTheDealHandsUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(DealHandsUseCase.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the play-card use case at all")
    void shouldNotRegisterThePlayCardUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(PlayCardUseCase.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the resolve-trick use case at all")
    void shouldNotRegisterTheResolveTrickUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(ResolveTrickUseCase.class)).isEmpty();
    }

    @Test
    @DisplayName("the rest of the application is unaffected: the flag withholds one feature, not the whole")
    void shouldLeaveTheRestOfTheApplicationAlone() {
        Assertions.assertThat(context.getBeanNamesForType(SecureRandomDeckShuffler.class))
                .as("the shuffler is ungated on purpose: it is stateless and reaches no table")
                .isNotEmpty();

        Assertions.assertThat(context.getBeanNamesForType(StartSessionUseCase.class))
                .as("session lifecycle is behind its own flag, which this test does not touch")
                .isNotEmpty();
    }
}
