package org.maglez.eop.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.usecase.GetLeaderboardUseCase;
import org.maglez.eop.usecase.NewGameUseCase;
import org.maglez.eop.usecase.PersistGameResultUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exercises the game-over routes end to end, through the real use cases, the real adapter and the
 * real database.
 *
 * <p>Nothing is stubbed. The interesting claims of these routes live in the seams: that the acting
 * player is resolved from the identity token, that a non-completed session is refused with 409, and
 * that a participant cannot start a new game. None of those survive being mocked out.
 *
 * <p>The flag-off class is a separate {@code @SpringBootTest} context so that the flag-on tests
 * can run in the shared context without paying for a second context start.
 */
@SpringBootTest(properties = "eop.features.game-over=true")
@AutoConfigureMockMvc
@DisplayName("Game-over endpoints (flag ON)")
class GameOverControllerIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String UNKNOWN_SESSION_ID = "00000000-0000-7000-8000-0000000000ff";

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("GET /leaderboard — reading the leaderboard")
    class GetLeaderboard {

        @Test
        @DisplayName("refuses a session that is still in progress with 409")
        void shouldRefuseInProgressSession() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            assertProblem(result, 409, "Game not completed");
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseMissingToken() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(table.sessionId(), null);

            assertProblem(result, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session as absent")
        void shouldReportUnknownSession() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(UNKNOWN_SESSION_ID, table.facilitator().playerToken());

            assertProblem(result, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseNonUuidSessionId() throws Exception {
            final var result = getLeaderboard("not-a-uuid", "any-token");

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(result);
        }
    }

    @Nested
    @DisplayName("POST /new-game — starting a new game")
    class PostNewGame {

        @Test
        @DisplayName("refuses a session that is still in progress with 409")
        void shouldRefuseInProgressSession() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(table.sessionId(), table.facilitator().playerToken());

            assertProblem(result, 409, "Game not completed");
        }

        @Test
        @DisplayName("refuses a participant, because starting a new game is the facilitator's job")
        void shouldRefuseAParticipant() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(table.sessionId(), table.seats().get(1).playerToken());

            assertProblem(result, 403, "Only the facilitator can start play");
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseMissingToken() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(table.sessionId(), null);

            assertProblem(result, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session as absent")
        void shouldReportUnknownSession() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(UNKNOWN_SESSION_ID, table.facilitator().playerToken());

            assertProblem(result, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseNonUuidSessionId() throws Exception {
            final var result = postNewGame("not-a-uuid", "any-token");

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(result);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MvcResult getLeaderboard(final String sessionId, final String playerToken) throws Exception {
        var request = get(SESSIONS + "/" + sessionId + "/leaderboard");
        if (playerToken != null) {
            request = request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult postNewGame(final String sessionId, final String playerToken) throws Exception {
        var request = post(SESSIONS + "/" + sessionId + "/new-game");
        if (playerToken != null) {
            request = request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    private Table startedTable() throws Exception {
        final var facilitator = createSession("Ada");
        final var second = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");
        final var table = new Table(facilitator.sessionId(), List.of(facilitator, second, third));
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/start")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isOk());
        return table;
    }

    private Admission createSession(final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\"}".formatted(displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\"}".formatted(displayName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private static Admission admissionFrom(final String body) {
        final var document = JsonPath.parse(body);
        final var playerId = document.read("$.playerId", String.class);
        final List<Map<String, Object>> players = document.read("$.session.players");
        final var seatOrder = players.stream()
                .filter(player -> playerId.equals(player.get("playerId")))
                .map(player -> (Integer) player.get("seatOrder"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the admitted player is missing from the session state"));
        return new Admission(
                document.read("$.session.sessionId", String.class),
                document.read("$.session.joinCode", String.class),
                playerId,
                document.read("$.playerToken", String.class),
                seatOrder);
    }

    private static void assertProblem(final MvcResult result, final int status, final String title)
            throws Exception {
        Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertProblemJson(result);
        Assertions.assertThat(
                        JsonPath.parse(result.getResponse().getContentAsString()).read("$.title", String.class))
                .isEqualTo(title);
    }

    private static void assertProblemJson(final MvcResult result) {
        Assertions.assertThat(result.getResponse().getContentType())
                .as("every refusal is an RFC 9457 problem document")
                .startsWith(PROBLEM_JSON);
    }

    private record Admission(String sessionId, String joinCode, String playerId, String playerToken,
            int seatOrder) {
    }

    private record Table(String sessionId, List<Admission> seats) {

        Admission facilitator() {
            return seats.get(0);
        }
    }
}
