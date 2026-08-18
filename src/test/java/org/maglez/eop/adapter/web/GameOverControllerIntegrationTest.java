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
import org.maglez.eop.usecase.PersistGameResultUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

    @Autowired
    private PersistGameResultUseCase persistGameResultUseCase;

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

    /**
     * Regression guards for the RFC 9457 contract on the leaderboard route.
     *
     * <p>Fault 1 of EOP-82 caused {@code GET /api/v1/sessions/{id}/leaderboard} to fall through
     * to Spring's static-resource handler when the {@code eop.features.game-over} flag was
     * {@code false}, producing a framework 404 whose detail contained
     * {@code "No static resource api/v1/sessions/…/leaderboard."} rather than an application
     * problem document. The flag is now {@code true} by default (verified by
     * {@code ShippedFeatureFlagDefaultsTest}), but a future refactor of the conditional, the
     * request-mapping path, or the {@code @ControllerAdvice} could reintroduce a framework 404
     * while the flag stays {@code true}. These tests fail in that world and pass only when the
     * route is mapped and the {@code GlobalExceptionHandler} is in the dispatch chain.
     *
     * <p>The 409 for a non-completed session is currently undocumented in
     * {@code docs/api/openapi.yml} for this operation — that reconciliation is tracked as EOP-83.
     */
    @Nested
    @DisplayName("RFC 9457 contract guards — leaderboard route")
    class Rfc9457ContractGuards {

        @Test
        @DisplayName("unknown session returns application 404, not a framework static-resource 404")
        void leaderboard404IsApplicationProblemNotFramework() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(UNKNOWN_SESSION_ID, table.facilitator().playerToken());

            // Status must be 404
            Assertions.assertThat(result.getResponse().getStatus())
                    .as("unknown session id returns 404")
                    .isEqualTo(404);

            // Content-Type must be application/problem+json — a framework 404 returns
            // application/json or text/html, not the RFC 9457 media type
            assertProblemJson(result);

            final var document = JsonPath.parse(result.getResponse().getContentAsString());

            // Title must be the application's own title, not Spring's "Not Found"
            Assertions.assertThat(document.read("$.title", String.class))
                    .as("title is the application's own, not the framework's")
                    .isEqualTo("Session not found");

            // Detail must contain the session identifier the caller supplied
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail names the unknown session identifier")
                    .contains(UNKNOWN_SESSION_ID);

            // THE REGRESSION GUARD: the framework 404 detail contains this literal string.
            // If this assertion fails the route has fallen through to static-resource handling.
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail must not contain the framework static-resource signature — "
                            + "if it does, the route is not mapped and the flag guard has been bypassed")
                    .doesNotContain("No static resource");
        }

        @Test
        @DisplayName("non-completed session returns 409 with full RFC 9457 body including detail")
        void leaderboard409ForNonCompletedSessionHasFullProblemBody() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            // Status
            Assertions.assertThat(result.getResponse().getStatus())
                    .as("in-progress session returns 409 Conflict")
                    .isEqualTo(409);

            // Content-Type
            assertProblemJson(result);

            final var document = JsonPath.parse(result.getResponse().getContentAsString());

            // Title
            Assertions.assertThat(document.read("$.title", String.class))
                    .as("title is 'Game not completed'")
                    .isEqualTo("Game not completed");

            // Detail — GlobalExceptionHandler builds: "Session " + sessionId + " is not yet completed."
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail names the session and states it is not yet completed")
                    .contains(table.sessionId())
                    .contains("not yet completed");
        }
    }

    @Nested
    @DisplayName("Happy path — completed session")
    class HappyPath {

        @Test
        @DisplayName("returns the leaderboard for a completed session")
        void shouldReturnLeaderboardForCompletedSession() throws Exception {
            final var table = completedTable();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("a completed session with a persisted result returns 200")
                    .isEqualTo(200);
            final var body = result.getResponse().getContentAsString();
            final var document = JsonPath.parse(body);
            final List<Map<String, Object>> rows = document.read("$.rows");
            Assertions.assertThat(rows)
                    .as("one leaderboard row per seated player")
                    .hasSize(3);
            Assertions.assertThat(document.read("$.sessionStatus", String.class))
                    .as("session status is COMPLETED")
                    .isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("facilitator can start a new game after the session is completed")
        void shouldAllowFacilitatorToStartNewGame() throws Exception {
            final var table = completedTable();

            final var result = postNewGame(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("facilitator starting a new game on a completed session returns 204")
                    .isEqualTo(204);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a session with three players, starts it, ends it via POST /end (forcing COMPLETED),
     * then persists the game result so the leaderboard is readable.
     */
    private Table completedTable() throws Exception {
        final var facilitator = createSession("Ada");
        final var second = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");
        final var table = new Table(facilitator.sessionId(), List.of(facilitator, second, third));
        // Start the game
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/start")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isOk());
        // End the session (forces COMPLETED via EndSessionUseCase)
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/end")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isNoContent());
        // Persist the game result (normally triggered by ResolveTrickUseCase; here we call directly
        // because EndSessionUseCase does not go through the trick-resolution path)
        persistGameResultUseCase.execute(UUID.fromString(table.sessionId()));
        return table;
    }

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
