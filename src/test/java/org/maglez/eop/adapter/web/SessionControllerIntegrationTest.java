package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.IdentityTokenHash;
import org.maglez.eop.entity.JoinCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Verifies the session endpoints end to end: the request travels through the
 * controller, the use cases, the real generators and a real database, and the
 * response is checked against the committed contract in {@code docs/api/openapi.yml}.
 *
 * <p>Nothing here stubs a collaborator. A test that mocked the repository would
 * assert that the controller calls the code the controller calls, which is a
 * tautology; the questions worth asking of this layer — does a refused credential
 * become a 403 rather than a 400, does a full table become a 409 rather than a
 * leaked invariant, is every unusable join code answered identically — can only be
 * answered by the assembled application.
 *
 * <p>The credential header is reached through {@link SessionController#PLAYER_TOKEN_HEADER}
 * rather than spelled out. Repeating the name here would let a rename pass the
 * build while breaking every client.
 *
 * <p>Every join that is expected to fail carries its own {@code X-Forwarded-For}
 * address. The rate limiter is a singleton in a cached Spring context, so failures
 * charged to the default peer address would accumulate across tests and eventually
 * make an unrelated test fail depending on the order the runner chose. Giving each
 * one its own address makes the class order-independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Session endpoints")
class SessionControllerIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** A lower-case 64 character run: the shape of a stored credential digest. */
    private static final String ANY_DIGEST = ".*\\b[0-9a-f]{64}\\b.*";

    /** The address window the limiter allows before it starts refusing. */
    private static final int TOLERATED_FAILURES = 10;

    /** Distinguishes the addresses and codes one test invents from another's. */
    private static final AtomicInteger SERIAL = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    /**
     * What a caller keeps after being admitted to a session.
     *
     * @param sessionId   the session it was admitted to
     * @param joinCode    the code that seats further players
     * @param playerId    the caller's own identifier
     * @param playerToken the caller's credential, in plaintext, as only it ever sees it
     * @param body        the whole response, for tests asking what was disclosed
     */
    private record Admission(String sessionId, String joinCode, String playerId, String playerToken, String body) {
    }

    // ---------------------------------------------------------------- helpers

    private Admission createSession(final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private ResultActions attemptJoin(final String joinCode, final String address) throws Exception {
        return mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                .header(FORWARDED_FOR, address)
                .contentType(MediaType.APPLICATION_JSON)
                .content(nameRequest("Hopeful")));
    }

    private ResultActions readState(final String sessionId, final String playerToken) throws Exception {
        final var request = get(SESSIONS + "/" + sessionId);
        if (playerToken != null) {
            request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions startPlay(final String sessionId, final String playerToken) throws Exception {
        return mockMvc.perform(post(SESSIONS + "/" + sessionId + "/start")
                .header(SessionController.PLAYER_TOKEN_HEADER, playerToken));
    }

    private static String nameRequest(final String displayName) {
        return "{\"displayName\":\"%s\"}".formatted(displayName);
    }

    private static Admission admissionFrom(final String body) {
        final var json = JsonPath.parse(body);
        return new Admission(
                json.read("$.session.sessionId"),
                json.read("$.session.joinCode"),
                json.read("$.playerId"),
                json.read("$.playerToken"),
                body);
    }

    /** A well-formed code no session holds, unique to the calling test. */
    private static String unheldCode() {
        final var alphabet = JoinCode.ALPHABET;
        var remaining = SERIAL.incrementAndGet();
        final var drawn = new StringBuilder("ZZZZZZ");
        for (int position = 0; position < JoinCode.LENGTH && remaining > 0; position++) {
            drawn.setCharAt(position, alphabet.charAt(remaining % alphabet.length()));
            remaining /= alphabet.length();
        }
        return drawn.toString();
    }

    /** An address no other test charges failures to. */
    private static String freshAddress() {
        return "203.0.113." + SERIAL.incrementAndGet();
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("creating a lobby")
    class CreatingALobby {

        @Test
        @DisplayName("seats the creator as the facilitator and says where the session lives")
        void shouldOpenALobby() throws Exception {
            final var created = mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Ada")))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.playerToken").isNotEmpty())
                    .andExpect(jsonPath("$.playerId").isNotEmpty())
                    .andExpect(jsonPath("$.session.status").value("LOBBY"))
                    .andExpect(jsonPath("$.session.joinCode").isNotEmpty())
                    .andExpect(jsonPath("$.session.players.length()").value(1))
                    .andExpect(jsonPath("$.session.players[0].displayName").value("Ada"))
                    .andExpect(jsonPath("$.session.players[0].seatOrder").value(0))
                    .andExpect(jsonPath("$.session.players[0].role").value("FACILITATOR"))
                    .andExpect(jsonPath("$.session.players[0].connectionStatus").value("CONNECTED"))
                    .andExpect(jsonPath("$.session.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.session.updatedAt").isNotEmpty())
                    .andReturn();
            final var admission = admissionFrom(created.getResponse().getContentAsString());

            assertThat(created.getResponse().getHeader("Location")).isEqualTo(SESSIONS + "/" + admission.sessionId());
            assertThat(admission.joinCode()).hasSize(JoinCode.LENGTH);
            assertThat(JoinCode.parse(admission.joinCode())).isPresent();
        }

        @Test
        @DisplayName("trims the display name rather than seating a padded one")
        void shouldTrimTheDisplayName() throws Exception {
            mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("  Ada  ")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.session.players[0].displayName").value("Ada"));
        }

        @Test
        @DisplayName("refuses a blank display name before the domain is asked")
        void shouldRejectABlankDisplayName() throws Exception {
            mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("   ")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        }

        @Test
        @DisplayName("refuses a display name one character past the limit")
        void shouldRejectAnOverlongDisplayName() throws Exception {
            mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("A".repeat(41))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        }

        @Test
        @DisplayName("refuses a request with no body at all")
        void shouldRejectAMissingBody() throws Exception {
            mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("joining a lobby")
    class JoiningALobby {

        @Test
        @DisplayName("seats the joiner in the next seat, matching the code however it was typed")
        void shouldSeatAJoiner() throws Exception {
            final var facilitator = createSession("Ada");

            mockMvc.perform(post(SESSIONS + "/" + facilitator.joinCode().toLowerCase(java.util.Locale.ROOT)
                            + "/players")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Grace")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.session.sessionId").value(facilitator.sessionId()))
                    .andExpect(jsonPath("$.session.joinCode").value(facilitator.joinCode()))
                    .andExpect(jsonPath("$.session.players.length()").value(2))
                    .andExpect(jsonPath("$.session.players[1].displayName").value("Grace"))
                    .andExpect(jsonPath("$.session.players[1].seatOrder").value(1))
                    .andExpect(jsonPath("$.session.players[1].role").value("PARTICIPANT"));
        }

        @Test
        @DisplayName("mints a credential of its own for the joiner")
        void shouldMintASeparateCredential() throws Exception {
            final var facilitator = createSession("Ada");

            final var joiner = joinSession(facilitator.joinCode(), "Grace");

            assertThat(joiner.playerToken()).isNotBlank().isNotEqualTo(facilitator.playerToken());
            assertThat(joiner.playerId()).isNotEqualTo(facilitator.playerId());
        }

        @Test
        @DisplayName("a joiner reads the same table the facilitator does, from the database")
        void shouldResyncFromStoredState() throws Exception {
            final var facilitator = createSession("Ada");
            final var joiner = joinSession(facilitator.joinCode(), "Grace");

            readState(facilitator.sessionId(), joiner.playerToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(facilitator.sessionId()))
                    .andExpect(jsonPath("$.joinCode").value(facilitator.joinCode()))
                    .andExpect(jsonPath("$.status").value("LOBBY"))
                    .andExpect(jsonPath("$.players.length()").value(2))
                    .andExpect(jsonPath("$.players[0].seatOrder").value(0))
                    .andExpect(jsonPath("$.players[1].seatOrder").value(1))
                    .andExpect(jsonPath("$.players[1].displayName").value("Grace"));
        }

        @Test
        @DisplayName("refuses a code no session holds")
        void shouldRefuseAnUnknownCode() throws Exception {
            attemptJoin(unheldCode(), freshAddress())
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("No such session"))
                    .andExpect(jsonPath("$.detail").value("No session matches that join code."));
        }

        @Test
        @DisplayName("answers every unusable code with the same status, title and detail, so the endpoint is not an oracle")
        void shouldNotDiscloseWhichCodesAreReal() throws Exception {
            final var wellFormed = unheldCode();
            final var attempts = List.of(
                    attemptJoin(wellFormed, freshAddress()),
                    attemptJoin("12345", freshAddress()),
                    attemptJoin("1234IU", freshAddress()));

            final var described = new ArrayList<String>();
            for (final var attempt : attempts) {
                final var response = attempt.andExpect(status().isNotFound()).andReturn().getResponse();
                final var json = JsonPath.parse(response.getContentAsString());
                described.add("%d|%s|%s".formatted(
                        response.getStatus(),
                        json.read("$.title", String.class),
                        json.read("$.detail", String.class)));
            }

            assertThat(described).containsOnly(described.get(0));
        }

        @Test
        @DisplayName("the only field that varies between refusals is the caller's own request path")
        void shouldOnlyEchoWhatTheCallerSent() throws Exception {
            final var attempted = unheldCode();

            final var body = attemptJoin(attempted, freshAddress())
                    .andExpect(status().isNotFound())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            final var instance = JsonPath.parse(body).read("$.instance", String.class);
            assertThat(instance).isEqualTo(SESSIONS + "/" + attempted + "/players");
        }

        @Test
        @DisplayName("refuses the seventh player rather than leaking a seat invariant as a 400")
        void shouldRefuseTheSeventhPlayer() throws Exception {
            final var facilitator = createSession("Ada");
            for (int seat = 1; seat < 6; seat++) {
                joinSession(facilitator.joinCode(), "Player " + seat);
            }

            attemptJoin(facilitator.joinCode(), freshAddress())
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Session is full"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maximum of 6")));
        }

        @Test
        @DisplayName("refuses a joiner once play has begun")
        void shouldRefuseAJoinAfterPlayStarted() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");
            startPlay(facilitator.sessionId(), facilitator.playerToken()).andExpect(status().isOk());

            attemptJoin(facilitator.joinCode(), freshAddress())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Session is not in the lobby"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("IN_PROGRESS")));
        }

        @Test
        @DisplayName("refuses a blank joiner name")
        void shouldRejectABlankJoinerName() throws Exception {
            final var facilitator = createSession("Ada");

            mockMvc.perform(post(SESSIONS + "/" + facilitator.joinCode() + "/players")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        }
    }

    @Nested
    @DisplayName("reading a session")
    class ReadingASession {

        @Test
        @DisplayName("refuses an unrecognised credential with a 403, not a 404")
        void shouldRefuseAnUnrecognisedCredential() throws Exception {
            final var facilitator = createSession("Ada");

            readState(facilitator.sessionId(), "not-a-credential-anyone-holds")
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Player not recognised"));
        }

        @Test
        @DisplayName("a missing credential is refused exactly as a wrong one is")
        void shouldTreatAMissingCredentialAsAWrongOne() throws Exception {
            final var facilitator = createSession("Ada");

            final var absent = readState(facilitator.sessionId(), null).andExpect(status().isForbidden());
            final var wrong = readState(facilitator.sessionId(), "not-a-credential-anyone-holds")
                    .andExpect(status().isForbidden());

            assertThat(absent.andReturn().getResponse().getContentAsString())
                    .isEqualTo(wrong.andReturn().getResponse().getContentAsString());
        }

        @Test
        @DisplayName("an unknown session is a 404, whatever credential was offered")
        void shouldRefuseAnUnknownSession() throws Exception {
            readState(UUID.randomUUID().toString(), "not-a-credential-anyone-holds")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Session not found"));
        }

        @Test
        @DisplayName("an identifier that is not a UUID is a 400, handled by the framework")
        void shouldRejectAMalformedIdentifier() throws Exception {
            readState("not-a-uuid", "not-a-credential-anyone-holds")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        }
    }

    @Nested
    @DisplayName("starting play")
    class StartingPlay {

        @Test
        @DisplayName("the facilitator closes a lobby of three")
        void shouldStartWithThreePlayers() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");

            startPlay(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(facilitator.sessionId()))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.players.length()").value(3));

            readState(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("two players is not a game")
        void shouldRefuseToStartWithTwoPlayers() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");

            startPlay(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Not enough players to start"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("at least 3")));
        }

        @Test
        @DisplayName("a participant cannot start play, even at a table that is ready")
        void shouldRefuseAParticipantStarting() throws Exception {
            final var facilitator = createSession("Ada");
            final var participant = joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");

            startPlay(facilitator.sessionId(), participant.playerToken())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Only the facilitator can start play"));
        }

        @Test
        @DisplayName("starting an already started session is refused")
        void shouldRefuseToStartTwice() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");
            startPlay(facilitator.sessionId(), facilitator.playerToken()).andExpect(status().isOk());

            startPlay(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Session is not in the lobby"));
        }

        @Test
        @DisplayName("a stranger cannot start play")
        void shouldRefuseAStrangerStarting() throws Exception {
            final var facilitator = createSession("Ada");

            startPlay(facilitator.sessionId(), "not-a-credential-anyone-holds")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Player not recognised"));
        }
    }

    @Nested
    @DisplayName("throttling guesses")
    class ThrottlingGuesses {

        @Test
        @DisplayName("tolerates ten wrong codes from one address and then refuses with a retry hint")
        void shouldThrottleAFloodOfGuesses() throws Exception {
            final var guesser = freshAddress();
            final var code = unheldCode();

            for (int attempt = 1; attempt <= TOLERATED_FAILURES; attempt++) {
                attemptJoin(code, guesser).andExpect(status().isNotFound());
            }

            final var refused = attemptJoin(code, guesser)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Too many join attempts"))
                    .andReturn()
                    .getResponse();

            // The limiter reads the wall clock, and the ten failures above took real
            // time to make, so the hint is a whole number of seconds short of the
            // window rather than exactly the window. Pinning 60 would make the test
            // fail on a slow machine; what matters is that the hint is present, is a
            // whole number, and does not exceed the window.
            assertThat(refused.getHeader("Retry-After"))
                    .isNotNull()
                    .satisfies(hint -> assertThat(Long.parseLong(hint)).isBetween(1L, 60L));
        }

        @Test
        @DisplayName("one exhausted address does not throttle another")
        void shouldThrottlePerAddress() throws Exception {
            final var guesser = freshAddress();
            final var code = unheldCode();
            for (int attempt = 1; attempt <= TOLERATED_FAILURES; attempt++) {
                attemptJoin(code, guesser).andExpect(status().isNotFound());
            }
            attemptJoin(code, guesser).andExpect(status().isTooManyRequests());

            attemptJoin(code, freshAddress()).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a throttled address can still be seated at a table it knows, because success is not charged")
        void shouldNotChargeSuccessfulJoins() throws Exception {
            final var facilitator = createSession("Ada");
            final var joiner = freshAddress();

            for (int attempt = 1; attempt <= TOLERATED_FAILURES; attempt++) {
                mockMvc.perform(post(SESSIONS + "/" + facilitator.joinCode() + "/players")
                                .header(FORWARDED_FOR, joiner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nameRequest("Guest " + attempt)))
                        .andExpect(attempt <= 5 ? status().isOk() : status().isConflict());
            }

            attemptJoin(unheldCode(), joiner).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("no response ever carries a stored credential digest")
    void shouldNeverDiscloseADigest() throws Exception {
        final var facilitator = createSession("Ada");
        final var participant = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");

        final var bodies = new ArrayList<String>();
        bodies.add(facilitator.body());
        bodies.add(participant.body());
        bodies.add(third.body());
        bodies.add(readState(facilitator.sessionId(), participant.playerToken())
                .andReturn().getResponse().getContentAsString());
        bodies.add(startPlay(facilitator.sessionId(), facilitator.playerToken())
                .andReturn().getResponse().getContentAsString());

        final var digests = List.of(
                IdentityTokenHash.of(facilitator.playerToken()).value(),
                IdentityTokenHash.of(participant.playerToken()).value(),
                IdentityTokenHash.of(third.playerToken()).value());

        assertThat(bodies).allSatisfy(body -> {
            assertThat(body).doesNotMatch(ANY_DIGEST);
            assertThat(digests).allSatisfy(digest -> assertThat(body).doesNotContain(digest));
        });
    }

    @Test
    @DisplayName("a player only ever receives its own credential, never another's")
    void shouldNotDiscloseAnotherPlayersCredential() throws Exception {
        final var facilitator = createSession("Ada");
        final var participant = joinSession(facilitator.joinCode(), "Grace");

        assertThat(participant.body()).doesNotContain(facilitator.playerToken());
        assertThat(readState(facilitator.sessionId(), facilitator.playerToken())
                .andReturn().getResponse().getContentAsString())
                .doesNotContain(facilitator.playerToken())
                .doesNotContain(participant.playerToken());
    }
}
