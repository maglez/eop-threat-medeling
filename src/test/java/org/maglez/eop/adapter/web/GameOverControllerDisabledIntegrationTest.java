package org.maglez.eop.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.usecase.GetLeaderboardUseCase;
import org.maglez.eop.usecase.NewGameUseCase;
import org.maglez.eop.usecase.PersistGameResultUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that the {@code eop.features.game-over} flag actually withholds the feature.
 *
 * <p>The flag is ON everywhere else: {@code src/test/resources/application.properties} pins it
 * true for the whole suite, and since EOP-82 {@code src/main/resources/application.yml} ships it
 * true as well. The {@code properties} override on this class is therefore load-bearing — it is
 * the only place the OFF position is exercised, and removing it would silently turn every
 * assertion below into a test of the ON position.
 *
 * <p>Asserts both that the controller bean is absent and that the routes return 404 — asserting
 * only on 404s would still pass if the controller existed but its handlers were mapped elsewhere.
 */
@SpringBootTest(properties = "eop.features.game-over=false")
@AutoConfigureMockMvc
@DisplayName("Game-over endpoints with the feature flag off")
class GameOverControllerDisabledIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    private static final String SOME_SESSION = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("does not create the controller at all")
    void shouldNotRegisterTheController() {
        Assertions.assertThat(context.getBeanNamesForType(GameOverController.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the use case beans")
    void shouldNotRegisterTheUseCaseBeans() {
        Assertions.assertThat(context.getBeanNamesForType(GetLeaderboardUseCase.class)).isEmpty();
        Assertions.assertThat(context.getBeanNamesForType(PersistGameResultUseCase.class)).isEmpty();
        Assertions.assertThat(context.getBeanNamesForType(NewGameUseCase.class)).isEmpty();
    }

    @Test
    @DisplayName("the leaderboard route is not exposed")
    void shouldNotExposeLeaderboard() throws Exception {
        mockMvc.perform(get(SESSIONS + "/" + SOME_SESSION + "/leaderboard"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the new-game route is not exposed")
    void shouldNotExposeNewGame() throws Exception {
        mockMvc.perform(post(SESSIONS + "/" + SOME_SESSION + "/new-game"))
                .andExpect(status().isNotFound());
    }
}
