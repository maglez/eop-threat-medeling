package org.maglez.eop.adapter.web;

import org.maglez.eop.entity.CardNotFoundException;
import org.maglez.eop.entity.CardNotInHandException;
import org.maglez.eop.entity.MustFollowSuitException;
import org.maglez.eop.entity.NoTamperingCardDealtException;
import org.maglez.eop.entity.NotFacilitatorException;
import org.maglez.eop.entity.NotYourSeatException;
import org.maglez.eop.entity.OutOfTurnException;
import org.maglez.eop.entity.PlayerMismatchException;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.SessionFullException;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.TooFewPlayersException;
import org.maglez.eop.entity.UnknownJoinCodeException;
import org.maglez.eop.usecase.TooManyJoinAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The application's single error handler.
 *
 * <p>Every 4xx and 5xx response the API produces is rendered here as RFC 9457
 * problem details (ADR-005). There is deliberately one handler and not one per
 * controller: a client should never have to discover that two endpoints report
 * failure in two different shapes.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} inherits Spring's handling
 * of framework-level failures — an unparseable body, an unsupported media type, a
 * path variable that is not a UUID — and those already emit problem details, so
 * they are not re-implemented below.
 *
 * <p>The mapping is deliberately narrow. Domain exceptions carry no HTTP
 * vocabulary, so this class is the only place that decides a status code, and
 * every mapping here has a unit test as the error handling rules require.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * A named card does not exist.
     *
     * @param exception the domain exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(CardNotFoundException.class)
    public ProblemDetail handleCardNotFound(final CardNotFoundException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Card not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The request asked for something the domain refuses to represent — an
     * out-of-range page, a rank that does not exist. The message is safe to
     * return because these exceptions are raised by our own guard clauses with
     * text written for a caller, not by a library with text written for a
     * maintainer.
     *
     * @param exception the rejected argument
     * @return a 400 problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A session identifier names no session.
     *
     * <p>Reported plainly, unlike an unknown join code. A session identifier is an
     * unguessable UUID, so concealing whether one exists protects nothing and only
     * makes a genuine bug harder to diagnose.
     *
     * @param exception the domain exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(final SessionNotFoundException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Session not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A join code matched nothing.
     *
     * <p>The status, title and detail are fixed strings and the exception's own
     * message is deliberately not used, so that no field of the response depends on
     * why the lookup failed. A code that was mistyped, a code that never existed and
     * a code belonging to an abandoned session must be indistinguishable: at roughly
     * thirty bits of entropy, an endpoint that confirmed which codes are real would
     * be an oracle worth querying (ADR-019).
     *
     * <p>The bodies are not byte-identical, because Spring fills in {@code instance}
     * with the request URI when a handler leaves it null, and the request URI carries
     * the attempted code. That echoes the caller's own input back at it and so
     * discloses nothing, which is why it is left alone rather than blanked: an empty
     * {@code instance} on this one mapping and a populated one everywhere else would
     * itself be a signal, and it would cost a real diagnostic in the logs of every
     * client that reports problem details verbatim.
     *
     * @return a 404 problem detail whose status, title and detail are the same for
     *         every unusable code
     */
    @ExceptionHandler(UnknownJoinCodeException.class)
    public ProblemDetail handleUnknownJoinCode() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("No such session");
        problem.setDetail("No session matches that join code.");
        return problem;
    }

    /**
     * The session has left the lobby, so it can be neither joined nor started.
     *
     * @param exception the domain exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(SessionNotJoinableException.class)
    public ProblemDetail handleSessionNotJoinable(final SessionNotJoinableException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Session is not in the lobby");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * Every seat at the table is taken.
     *
     * @param exception the domain exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(SessionFullException.class)
    public ProblemDetail handleSessionFull(final SessionFullException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Session is full");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The facilitator tried to start play with fewer than three players.
     *
     * <p>A conflict rather than a bad request: the request was well formed and the
     * refusal is about the state of the table, which the caller can change by
     * waiting for another player.
     *
     * @param exception the domain exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(TooFewPlayersException.class)
    public ProblemDetail handleTooFewPlayers(final TooFewPlayersException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Not enough players to start");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The request carried no recognisable player credential.
     *
     * <p>Forbidden rather than unauthorised. There is no authentication scheme
     * here — no realm, no challenge, nothing a client could retry differently — and
     * a 401 is required to carry a {@code WWW-Authenticate} header, so emitting one
     * would advertise a challenge that does not exist (ADR-019). The request was
     * understood and refused, which is what 403 means.
     *
     * @param exception the domain exception, which never carries the credential
     * @return a 403 problem detail
     */
    @ExceptionHandler(PlayerNotRecognisedException.class)
    public ProblemDetail handlePlayerNotRecognised(final PlayerNotRecognisedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Player not recognised");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A seated participant tried to start play.
     *
     * @param exception the domain exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(NotFacilitatorException.class)
    public ProblemDetail handleNotFacilitator(final NotFacilitatorException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Only the facilitator can start play");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A play described a seat other than the one the requesting player occupies.
     *
     * <p>Forbidden rather than a bad request, and the distinction is the point. The
     * body was well formed; what was refused was an attempt to act as somebody else,
     * so this is an authorisation failure and it must not be reported as a validation
     * one. An earlier revision of the domain threw {@link IllegalArgumentException}
     * here and so rendered a 400, which told an attacker their request was merely
     * malformed and told the logs the same untruth.
     *
     * <p>The message is returned because it names only two seat numbers, and every
     * player already receives every seat number in the session state. Nothing in it
     * is a secret.
     *
     * @param exception the domain exception, carrying the occupied and claimed seats
     * @return a 403 problem detail
     */
    @ExceptionHandler(NotYourSeatException.class)
    public ProblemDetail handleNotYourSeat(final NotYourSeatException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("That is not your seat");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A play named a player other than the one occupying the seat it was played from.
     *
     * <p>The sibling of {@link #handleNotYourSeat}, and forbidden for the same
     * reason. In ordinary play it is unreachable, because the seat is settled from
     * the credential before this is checked; it fires when a request names somebody
     * else, or when hands have been filed against the wrong seats, and in the second
     * case it is the difference between a loud refusal and two players quietly
     * playing each other's cards.
     *
     * <p>The detail is fixed and the exception's message deliberately unused. That
     * message names both player identifiers, which is what makes it worth logging and
     * exactly what makes it unfit to return; the identifiers stay reachable through
     * the exception's accessors for whoever handles the incident.
     *
     * @return a 403 problem detail that names no player
     */
    @ExceptionHandler(PlayerMismatchException.class)
    public ProblemDetail handlePlayerMismatch() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("That play does not belong to you");
        problem.setDetail("The play does not match the requesting player's identity.");
        return problem;
    }

    /**
     * A player played out of turn.
     *
     * <p>A conflict rather than a bad request: the request was well formed and the
     * refusal is about the state of the trick, which the caller fixes by waiting.
     * The message names the seat that is to play, which saves the client a second
     * request and discloses nothing — whose turn it is, is public at the table.
     *
     * @param exception the domain exception, carrying the expected and attempted seats
     * @return a 409 problem detail
     */
    @ExceptionHandler(OutOfTurnException.class)
    public ProblemDetail handleOutOfTurn(final OutOfTurnException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Not your turn");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A player holding the led suit played a different one.
     *
     * <p>Unprocessable rather than a bad request: every field parsed and every
     * identifier resolved, and the request was refused by a rule of the game. The
     * message names the led suit and the suit attempted, both of which are face up
     * the moment the card is played.
     *
     * @param exception the domain exception, carrying the led and attempted suits
     * @return a 422 problem detail
     */
    @ExceptionHandler(MustFollowSuitException.class)
    public ProblemDetail handleMustFollowSuit(final MustFollowSuitException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("You must follow suit");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A play named a card the hand does not hold.
     *
     * <p>The detail is built from the card identifier alone rather than from the
     * exception's message, which also names the hand. A hand identifier is an
     * internal key the caller has no use for; echoing it would widen the response
     * for no benefit to whoever reads it.
     *
     * <p>This covers a card played twice, a card that belongs to somebody else, and
     * a request that named no card at all — the domain answers all three the same
     * way, so that a missing card field is a client error rather than a server one.
     *
     * @param exception the domain exception, carrying the hand and the card
     * @return a 422 problem detail naming only the card
     */
    @ExceptionHandler(CardNotInHandException.class)
    public ProblemDetail handleCardNotInHand(final CardNotInHandException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Card not in hand");
        problem.setDetail("Your hand does not hold card " + exception.cardId() + ".");
        return problem;
    }

    /**
     * No Tampering card was dealt, so the opening leader could not be derived.
     *
     * <p>A server fault, not a client one, and the only mapping here that says so.
     * The deck is seeded reference data and every valid deck contains the suit, so
     * reaching this means the deck the application dealt from was not the deck it
     * ships with. The count is logged because it is the one number that identifies
     * which deck was dealt, and withheld from the response because a caller can do
     * nothing with it.
     *
     * @param exception the domain exception, carrying how many cards were dealt
     * @return a 500 problem detail with no internal information
     */
    @ExceptionHandler(NoTamperingCardDealtException.class)
    public ProblemDetail handleNoTamperingCardDealt(final NoTamperingCardDealtException exception) {
        LOG.error("No tampering card among the {} cards dealt, so no opening leader could be derived",
                exception.cardsDealt(), exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }

    /**
     * Too many failed join attempts from one source.
     *
     * <p>Returned as a {@link ResponseEntity} rather than a bare problem detail so
     * it can carry {@code Retry-After}. This is not politeness about load: a
     * six-character code is unguessable only while guessing is slow, so this
     * response is a primary security control rather than a courtesy (ADR-019).
     *
     * @param exception the refusal, carrying how long to wait
     * @return a 429 problem detail with a {@code Retry-After} header
     */
    @ExceptionHandler(TooManyJoinAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyJoinAttempts(final TooManyJoinAttemptsException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many join attempts");
        problem.setDetail(exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .body(problem);
    }

    /**
     * Anything unanticipated.
     *
     * <p>The cause is logged and deliberately not returned. An unexpected failure
     * is the one case where the message is most likely to describe internals, and
     * the application is deployed on a public address with no authentication, so
     * the response says nothing a caller could use to map the inside of the
     * system.
     *
     * @param exception the unexpected failure
     * @return a 500 problem detail with no internal information
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception exception) {
        LOG.error("Unhandled exception serving a request", exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }
}
