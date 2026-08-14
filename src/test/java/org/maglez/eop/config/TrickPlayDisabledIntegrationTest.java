package org.maglez.eop.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.adapter.security.SecureRandomDeckShuffler;
import org.maglez.eop.adapter.web.TrickController;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.GetTrickStateUseCase;
import org.maglez.eop.usecase.PlayCardUseCase;
import org.maglez.eop.usecase.ReadOwnHandUseCase;
import org.maglez.eop.usecase.ResolveTrickUseCase;
import org.maglez.eop.usecase.StartSessionUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserts that {@code eop.features.trick-play} withholds trick play when it is off.
 *
 * <p>The suite runs with every flag on, because a suite that ran with the feature off would be
 * testing the absence of it. This one class overrides the flag back to false, which costs a second
 * Spring context, and that cost is the price of knowing the flag works: a flag nobody tests in its
 * off position is a flag that will not be trusted in an incident.
 *
 * <p>Both halves of the off position are asserted here, and they are not the same assertion. The
 * routes must answer 404, because that is what the contract promises a client while the flag is
 * off. The beans must be absent, because a 404 on its own would also be satisfied by a controller
 * that existed with its handlers mapped somewhere else, and it is
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} withholding the
 * code rather than a branch at request time that ADR-013 asks for. Asserting only the status would
 * pass for a design the flag rule forbids.
 *
 * <p>They are asserted in one class rather than two on purpose. A second class carrying the same
 * property override would still get its own context, because the MockMvc auto-configuration is part
 * of the cache key, so splitting the pair would buy nothing and pay for a third context.
 *
 * <p>What the flag withholds is worth stating precisely. An earlier slice put five trick-play tables
 * and two ports into the application, and the adapter implementing those ports is an unconditional
 * {@code @Repository} that is created either way. So the flag does not withhold the capability to
 * write a hand, a trick or a play; it withholds every caller of it — the five use cases and the
 * controller that reaches them over HTTP.
 *
 * <p>The last two tests are the counterweight. A flag that took the rest of the application down
 * with it would be worse than no flag, so the shuffler — deliberately ungated, because a stateless
 * permutation reaches no table — the session lifecycle beans, and a live session route are all
 * asserted to survive. The session route matters most: trick play and the lobby are separate flags,
 * and the point of separating them is that the lobby can be live while trick play is held back.
 *
 * <p>That last test creates a session rather than reading one, and the reason is worth recording
 * because the obvious version of it does not work. A read of some invented session identifier
 * answers 404 whether the route is served or not, since the session is looked up before the
 * credential is checked, so it cannot tell a withheld route from an absent session — the very
 * distinction the test exists to make. Creating a session needs no identifier to already exist, so
 * its 201 can only mean the route is there.
 */
@SpringBootTest(properties = "eop.features.trick-play=false")
@AutoConfigureMockMvc
@DisplayName("Trick play with the feature flag off")
class TrickPlayDisabledIntegrationTest {

    /** A session identifier that need not exist: the routes are gone before any lookup happens. */
    private static final String SOME_SESSION = UUID.randomUUID().toString();

    /** The smallest body the play route would accept, so the 404 cannot be a validation failure. */
    private static final String A_PLAY = "{\"cardId\":\"%s\"}".formatted(UUID.randomUUID());

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("does not create the read-own-hand use case at all")
    void shouldNotRegisterTheReadOwnHandUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(ReadOwnHandUseCase.class)).isEmpty();
    }

    /**
     * Asserts the read that answers whose turn it is is withheld along with the writers.
     *
     * <p>It is a read, and it names no card any seat holds, so nothing about it is dangerous. It is
     * gated all the same, because with the flag off no hand has been dealt and there is no state of
     * play to report: the route could only ever answer a conflict. A read left behind a withheld
     * feature is a route whose every answer is a refusal, which is a worse thing to publish than no
     * route at all.
     */
    @Test
    @DisplayName("does not create the trick-state use case at all")
    void shouldNotRegisterTheGetTrickStateUseCase() {
        Assertions.assertThat(context.getBeanNamesForType(GetTrickStateUseCase.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the controller, so the routes are absent rather than disabled")
    void shouldNotRegisterTheTrickController() {
        Assertions.assertThat(context.getBeanNamesForType(TrickController.class)).isEmpty();
    }

    /** Asserts the deal route is not served while the flag is off. */
    @Test
    @DisplayName("withholds the deal route")
    void shouldNotServeTheDealRoute() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{id}/deal", SOME_SESSION))
                .andExpect(status().isNotFound());
    }

    /** Asserts the own-hand route is not served while the flag is off. */
    @Test
    @DisplayName("withholds the own-hand route")
    void shouldNotServeTheOwnHandRoute() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/hand", SOME_SESSION))
                .andExpect(status().isNotFound());
    }

    /** Asserts the play route is not served while the flag is off, body and all. */
    @Test
    @DisplayName("withholds the play route")
    void shouldNotServeThePlayRoute() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{id}/plays", SOME_SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_PLAY))
                .andExpect(status().isNotFound());
    }

    /** Asserts the state-of-play route is not served while the flag is off. */
    @Test
    @DisplayName("withholds the state-of-play route")
    void shouldNotServeTheTrickStateRoute() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/tricks/current", SOME_SESSION))
                .andExpect(status().isNotFound());
    }

    /** Asserts the resolve route is not served while the flag is off. */
    @Test
    @DisplayName("withholds the resolve route")
    void shouldNotServeTheResolveRoute() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{id}/tricks/current/resolve", SOME_SESSION))
                .andExpect(status().isNotFound());
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

    /** Asserts the lobby is still served, which is the whole reason the two flags are separate. */
    @Test
    @DisplayName("the lobby is still live: a session can still be created while trick play is withheld")
    void shouldStillServeTheSessionRoutes() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ada\"}"))
                .andExpect(status().isCreated());
    }
}
