package org.maglez.eop.adapter.web;

import org.maglez.eop.entity.CardNotFoundException;
import org.maglez.eop.entity.NotFacilitatorException;
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
     * <p>The title and detail are fixed strings and the exception's own message is
     * deliberately not used, so that every failed lookup produces a byte-identical
     * response. A code that was mistyped, a code that never existed and a code
     * belonging to an abandoned session must be indistinguishable: at roughly
     * thirty bits of entropy, an endpoint that confirmed which codes are real would
     * be an oracle worth querying (ADR-019).
     *
     * @return a 404 problem detail carrying no information about the attempt
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
