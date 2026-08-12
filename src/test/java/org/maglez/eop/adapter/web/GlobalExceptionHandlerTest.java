package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.AlreadyPlayedInTrickException;
import org.maglez.eop.entity.CardAlreadyPlayedException;
import org.maglez.eop.entity.CardNotFoundException;
import org.maglez.eop.entity.CardNotInHandException;
import org.maglez.eop.entity.HandAlreadyDealtException;
import org.maglez.eop.entity.IdentityTokenHash;
import org.maglez.eop.entity.MustFollowSuitException;
import org.maglez.eop.entity.NoTamperingCardDealtException;
import org.maglez.eop.entity.NotFacilitatorException;
import org.maglez.eop.entity.NotYourSeatException;
import org.maglez.eop.entity.OutOfTurnException;
import org.maglez.eop.entity.PlayerMismatchException;
import org.maglez.eop.entity.PlayerNotInSessionException;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.SessionFullException;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.SessionStatus;
import org.maglez.eop.entity.StrideCategory;
import org.maglez.eop.entity.TooFewPlayersException;
import org.maglez.eop.entity.TrickAlreadyOpenException;
import org.maglez.eop.entity.UnknownJoinCodeException;
import org.maglez.eop.entity.WinningPlayNotInTrickException;
import org.maglez.eop.usecase.TooManyJoinAttemptsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The error handling rules require a test for every mapped exception, because
 * this class is a single point of failure: a bug here hides every error the API
 * would otherwise report.
 *
 * <p>Handlers are called directly rather than through a request. Each one is a
 * pure function from an exception to a problem detail, so a servlet container
 * would add nothing but seconds; the status and title of every mapping are
 * pinned here, and the wiring that chooses a handler is proved once by the
 * endpoint tests.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    /** A session identifier, reused so the tests read as one conversation. */
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    /** A player identifier distinct from the session's. */
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

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

    @Nested
    @DisplayName("mapping a session failure")
    class SessionFailures {

        @Test
        @DisplayName("an unknown session identifier is a 404 naming it, because a UUID is not worth concealing")
        void shouldMapSessionNotFoundTo404() {
            final ProblemDetail problem = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("Session not found");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("an unknown join code is a 404 with a fixed body that names nothing")
        void shouldMapUnknownJoinCodeTo404() {
            final ProblemDetail problem = handler.handleUnknownJoinCode();

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("No such session");
            assertThat(problem.getDetail()).isEqualTo("No session matches that join code.");
        }

        @Test
        @DisplayName("every unknown join code produces the same body, so the endpoint is no oracle")
        void shouldMapEveryUnknownJoinCodeIdentically() {
            final ProblemDetail mistyped = handler.handleUnknownJoinCode();
            final ProblemDetail neverExisted = handler.handleUnknownJoinCode();

            assertThat(neverExisted.getStatus()).isEqualTo(mistyped.getStatus());
            assertThat(neverExisted.getTitle()).isEqualTo(mistyped.getTitle());
            assertThat(neverExisted.getDetail()).isEqualTo(mistyped.getDetail());
        }

        @Test
        @DisplayName("a session past the lobby is a 409 naming the status it reached")
        void shouldMapSessionNotJoinableTo409() {
            final ProblemDetail problem =
                    handler.handleSessionNotJoinable(new SessionNotJoinableException(SESSION_ID, SessionStatus.IN_PROGRESS));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Session is not in the lobby");
            assertThat(problem.getDetail()).contains("IN_PROGRESS");
        }

        @Test
        @DisplayName("a full table is a 409 naming the capacity")
        void shouldMapSessionFullTo409() {
            final ProblemDetail problem = handler.handleSessionFull(new SessionFullException(SESSION_ID, 6));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Session is full");
            assertThat(problem.getDetail()).contains("maximum of 6 players");
        }

        @Test
        @DisplayName("too few players is a 409, because waiting for another player fixes it")
        void shouldMapTooFewPlayersTo409() {
            final ProblemDetail problem = handler.handleTooFewPlayers(new TooFewPlayersException(SESSION_ID, 2, 3));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Not enough players to start");
            assertThat(problem.getDetail()).contains("2 players").contains("at least 3");
        }

        @Test
        @DisplayName("an unrecognised credential is a 403, not a 401, because there is no challenge to offer")
        void shouldMapPlayerNotRecognisedTo403() {
            final ProblemDetail problem = handler.handlePlayerNotRecognised(new PlayerNotRecognisedException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getTitle()).isEqualTo("Player not recognised");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("a participant trying to start play is a 403")
        void shouldMapNotFacilitatorTo403() {
            final ProblemDetail problem = handler.handleNotFacilitator(new NotFacilitatorException(SESSION_ID, PLAYER_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getTitle()).isEqualTo("Only the facilitator can start play");
            assertThat(problem.getDetail()).contains(PLAYER_ID.toString());
        }
    }

    @Nested
    @DisplayName("answering a caller who is not in the session")
    class Membership {

        @Test
        @DisplayName("a stranger acting on a session is a 404, not a 403")
        void shouldMapPlayerNotInSessionTo404() {
            final ProblemDetail problem = handler.handlePlayerNotInSession(new PlayerNotInSessionException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("Session not found");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("the whole body equals the body for a session that does not exist, not merely the status")
        void shouldAnswerAStrangerExactlyAsAMissingSessionIsAnswered() {
            final ProblemDetail missing = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));
            final ProblemDetail stranger = handler.handlePlayerNotInSession(new PlayerNotInSessionException(SESSION_ID));

            assertThat(stranger)
                    .as("two 404s with different titles or details would be as good an oracle as a 403")
                    .isEqualTo(missing);
            assertThat(stranger.getStatus()).isEqualTo(missing.getStatus());
            assertThat(stranger.getTitle()).isEqualTo(missing.getTitle());
            assertThat(stranger.getDetail()).isEqualTo(missing.getDetail());
            assertThat(stranger.getType()).isEqualTo(missing.getType());
            assertThat(stranger.getProperties()).isEqualTo(missing.getProperties());
        }

        @Test
        @DisplayName("the two exceptions carry identical messages, which is what keeps the two details equal")
        void shouldCarryTheSameMessageAsAMissingSession() {
            assertThat(new PlayerNotInSessionException(SESSION_ID).getMessage())
                    .as("both handlers use getMessage() as the detail, so parity is decided here")
                    .isEqualTo(new SessionNotFoundException(SESSION_ID).getMessage());
        }

        @Test
        @DisplayName("no field says why the lookup failed")
        void shouldNameNeitherPlayerNorSeatNorMembership() {
            final ProblemDetail problem = handler.handlePlayerNotInSession(new PlayerNotInSessionException(SESSION_ID));

            assertThat(problem.getTitle() + " " + problem.getDetail())
                    .doesNotContainIgnoringCase("player")
                    .doesNotContainIgnoringCase("seat")
                    .doesNotContainIgnoringCase("member")
                    .doesNotContainIgnoringCase("authoris")
                    .doesNotContainIgnoringCase("forbidden");
        }
    }

    @Nested
    @DisplayName("throttling a guesser")
    class Throttling {

        @Test
        @DisplayName("too many join attempts is a 429 whose Retry-After states the wait in seconds")
        void shouldMapTooManyJoinAttemptsTo429WithRetryAfter() {
            final ResponseEntity<ProblemDetail> response =
                    handler.handleTooManyJoinAttempts(new TooManyJoinAttemptsException(Duration.ofSeconds(45)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(response.getBody().getTitle()).isEqualTo("Too many join attempts");
            assertThat(response.getBody().getDetail()).contains("retry after 45 seconds");
        }

        @Test
        @DisplayName("Retry-After is a whole number of seconds, because the header admits nothing finer")
        void shouldRoundRetryAfterDownToWholeSeconds() {
            final ResponseEntity<ProblemDetail> response =
                    handler.handleTooManyJoinAttempts(new TooManyJoinAttemptsException(Duration.ofMillis(1500)));

            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("no refusal echoes a credential, in plaintext or as a digest")
    void shouldNeverLeakACredential() {
        final String plaintext = "grace-plaintext-token";
        final String digest = IdentityTokenHash.of(plaintext).value();

        final List<String> details = List.of(
                handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID)).getDetail(),
                handler.handleUnknownJoinCode().getDetail(),
                handler.handleSessionNotJoinable(new SessionNotJoinableException(SESSION_ID, SessionStatus.LOBBY)).getDetail(),
                handler.handleSessionFull(new SessionFullException(SESSION_ID, 6)).getDetail(),
                handler.handleTooFewPlayers(new TooFewPlayersException(SESSION_ID, 2, 3)).getDetail(),
                handler.handlePlayerNotRecognised(new PlayerNotRecognisedException(SESSION_ID)).getDetail(),
                handler.handleNotFacilitator(new NotFacilitatorException(SESSION_ID, PLAYER_ID)).getDetail());

        assertThat(details).noneMatch(detail -> detail.contains(plaintext))
                .noneMatch(detail -> detail.contains(digest))
                .noneMatch(detail -> detail.matches(".*\\b[0-9a-f]{64}\\b.*"));
    }

    @Nested
    @DisplayName("refuses a play without saying more than the refusal needs to")
    class TrickPlayFailures {

        @Test
        @DisplayName("a play claiming a seat the caller does not occupy is 403, and names both seats")
        void shouldMapNotYourSeatToForbidden() {
            final ProblemDetail problem = handler.handleNotYourSeat(new NotYourSeatException(2, 1));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getDetail()).contains("2").contains("1");
        }

        @Test
        @DisplayName("a play naming someone who does not hold the seat is 403, and discloses neither player")
        void shouldMapPlayerMismatchToForbiddenWithoutNamingEitherPlayer() {
            final UUID occupant = UUID.randomUUID();
            final UUID named = UUID.randomUUID();
            final PlayerMismatchException exception = new PlayerMismatchException(1, occupant, named);

            final ProblemDetail problem = handler.handlePlayerMismatch();

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getDetail())
                    .doesNotContain(occupant.toString())
                    .doesNotContain(named.toString());
            assertThat(exception.getMessage())
                    .doesNotContain(occupant.toString())
                    .doesNotContain(named.toString());
            assertThat(exception.occupant()).isEqualTo(occupant);
            assertThat(exception.namedPlayer()).isEqualTo(named);
        }

        @Test
        @DisplayName("a play out of turn is 409, and says whose turn it is so no second request is needed")
        void shouldMapOutOfTurnToConflict() {
            final ProblemDetail problem = handler.handleOutOfTurn(new OutOfTurnException(1, 2));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getDetail()).contains("1");
        }

        @Test
        @DisplayName("a refusal to follow suit is 422, and names both suits because both are face up")
        void shouldMapMustFollowSuitToUnprocessable() {
            final ProblemDetail problem = handler.handleMustFollowSuit(
                    new MustFollowSuitException(StrideCategory.SPOOFING, StrideCategory.TAMPERING));

            assertThat(problem.getStatus()).isEqualTo(422);
            assertThat(problem.getDetail()).contains("SPOOFING").contains("TAMPERING");
        }

        @Test
        @DisplayName("a card the hand does not hold is 422, naming the card and never the hand")
        void shouldMapCardNotInHandToUnprocessableWithoutTheHandIdentifier() {
            final UUID handId = UUID.randomUUID();
            final UUID cardId = UUID.randomUUID();

            final ProblemDetail problem = handler.handleCardNotInHand(new CardNotInHandException(handId, cardId));

            assertThat(problem.getStatus()).isEqualTo(422);
            assertThat(problem.getDetail())
                    .contains(cardId.toString())
                    .doesNotContain(handId.toString());
        }

        @Test
        @DisplayName("a deck holding no tampering card is a server fault, 500, and says nothing about the deck")
        void shouldMapNoTamperingCardDealtToServerError() {
            final ProblemDetail problem =
                    handler.handleNoTamperingCardDealt(new NoTamperingCardDealtException(78));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problem.getDetail()).isEqualTo("The request could not be completed.");
            assertThat(problem.getDetail()).doesNotContain("78");
        }
    }

    @Nested
    @DisplayName("refuses a request that lost a race, without saying who won it")
    class TrickPlayConflicts {

        /** A trick identifier, which is an internal key no response should echo. */
        private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

        @Test
        @DisplayName("dealing twice is a 409 naming the session the caller supplied")
        void shouldMapHandAlreadyDealtToConflict() {
            final ProblemDetail problem = handler.handleHandAlreadyDealt(new HandAlreadyDealtException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Hands already dealt");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("losing the race to open a trick is a 409 naming the sequence, because re-reading the session fixes it")
        void shouldMapTrickAlreadyOpenToConflict() {
            final ProblemDetail problem = handler.handleTrickAlreadyOpen(new TrickAlreadyOpenException(SESSION_ID, 3));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Trick already open");
            assertThat(problem.getDetail()).contains("3");
        }

        @Test
        @DisplayName("a second play from one seat is a 409 naming the seat and never the trick")
        void shouldMapAlreadyPlayedInTrickToConflictWithoutTheTrickIdentifier() {
            final ProblemDetail problem =
                    handler.handleAlreadyPlayedInTrick(new AlreadyPlayedInTrickException(TRICK_ID, 2));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Already played in this trick");
            assertThat(problem.getDetail()).contains("2").doesNotContain(TRICK_ID.toString());
        }

        @Test
        @DisplayName("a card played twice into one trick is a 409 naming the card and never the trick")
        void shouldMapCardAlreadyPlayedToConflictWithoutTheTrickIdentifier() {
            final UUID cardId = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

            final ProblemDetail problem =
                    handler.handleCardAlreadyPlayed(new CardAlreadyPlayedException(TRICK_ID, cardId));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Card already played");
            assertThat(problem.getDetail()).contains(cardId.toString()).doesNotContain(TRICK_ID.toString());
        }

        @Test
        @DisplayName("the two conflicts on a trick are told apart, so a caller learns which of them it hit")
        void shouldNotCollapseTheSeatConflictIntoTheCardConflict() {
            final UUID cardId = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

            final ProblemDetail seatConflict =
                    handler.handleAlreadyPlayedInTrick(new AlreadyPlayedInTrickException(TRICK_ID, 2));
            final ProblemDetail cardConflict =
                    handler.handleCardAlreadyPlayed(new CardAlreadyPlayedException(TRICK_ID, cardId));

            assertThat(seatConflict.getStatus()).isEqualTo(cardConflict.getStatus());
            assertThat(seatConflict.getTitle())
                    .as("one refuses a second play from a seat, the other one card appearing twice")
                    .isNotEqualTo(cardConflict.getTitle());
            assertThat(seatConflict.getDetail()).isNotEqualTo(cardConflict.getDetail());
        }

        @Test
        @DisplayName("a winner from another trick is 422 and not 409, because no later state makes a retry work")
        void shouldMapWinningPlayNotInTrickToUnprocessableWithoutEitherIdentifier() {
            final UUID playId = UUID.fromString("00000000-0000-7000-8000-0000000000d4");

            final ProblemDetail problem =
                    handler.handleWinningPlayNotInTrick(new WinningPlayNotInTrickException(TRICK_ID, playId));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(problem.getStatus()).isNotEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("That play is not in this trick");
            assertThat(problem.getDetail())
                    .isEqualTo("The play named as the winner was not made into the trick being resolved.")
                    .doesNotContain(TRICK_ID.toString())
                    .doesNotContain(playId.toString());
        }
    }
}
