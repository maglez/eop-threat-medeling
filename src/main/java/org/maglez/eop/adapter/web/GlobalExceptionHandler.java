package org.maglez.eop.adapter.web;

import org.maglez.eop.entity.AlreadyPlayedInTrickException;
import org.maglez.eop.entity.CardAlreadyPlayedException;
import org.maglez.eop.entity.CardNotFoundException;
import org.maglez.eop.entity.CardNotInHandException;
import org.maglez.eop.entity.HandAlreadyDealtException;
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
import org.maglez.eop.entity.TooFewPlayersException;
import org.maglez.eop.entity.TrickAlreadyOpenException;
import org.maglez.eop.entity.UnknownJoinCodeException;
import org.maglez.eop.entity.WinningPlayNotInTrickException;
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
     * <p>Scoped to existence. The argument above is about whether a session can be
     * found, not about who may see it, and it does not license candour on a
     * caller's membership: an identifier being unguessable says nothing about a
     * stranger who legitimately holds one. Membership is answered by
     * {@link #handlePlayerNotInSession}, whose body this one must match field for
     * field, so that a caller cannot tell a session it may not see from a session
     * that does not exist.
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
     * The caller is not a player in the session it is acting on.
     *
     * <p>Answered as a 404 that is indistinguishable from
     * {@link #handleSessionNotFound}, and not as a 403. A 403 would confirm that
     * the session exists, which tells a stranger holding a guessed or leaked
     * identifier that it guessed correctly — the one thing this response must not
     * reveal. A caller that is genuinely a member never reaches here, so nothing
     * legitimate is made harder to diagnose.
     *
     * <p>The parity is field for field and not merely status for status: the title
     * is the same fixed string and the detail is
     * {@link PlayerNotInSessionException}'s message, which that class constructs to
     * be identical to {@link SessionNotFoundException}'s for the same identifier.
     * No field may name a player, a seat, membership or authorisation, or vary with
     * why the lookup failed, because any such difference is the oracle the 404 was
     * chosen to deny. The unit test asserts equality of the two bodies rather than
     * equality of the two statuses, since two 404s with different titles would leak
     * just as effectively (ADR-023).
     *
     * <p>This follows {@link #handleUnknownJoinCode} and its reasoning rather than
     * minting a fresh shape, with one deliberate difference: that handler blanks the
     * identifier because a six-character join code is guessable, whereas the session
     * identifier echoed here is a value the caller supplied and cannot learn
     * anything from.
     *
     * @param exception the refusal, carrying the session identifier and nothing else
     * @return a 404 problem detail equal to the one for a session that does not exist
     */
    @ExceptionHandler(PlayerNotInSessionException.class)
    public ProblemDetail handlePlayerNotInSession(final PlayerNotInSessionException exception) {
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
     * Hands have already been dealt in this session.
     *
     * <p>A conflict rather than a rejected argument: the request was well formed and
     * would have succeeded a moment earlier. Two facilitators dealing at once is the
     * ordinary way to reach this, and the loser is told the state of the world
     * changed rather than that it asked for something impossible.
     *
     * <p>The detail names the session, which the caller supplied, and nothing about
     * who dealt or when.
     *
     * @param exception the refusal, carrying the session identifier
     * @return a 409 problem detail
     */
    @ExceptionHandler(HandAlreadyDealtException.class)
    public ProblemDetail handleHandAlreadyDealt(final HandAlreadyDealtException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Hands already dealt");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A trick with this sequence number is already open in the session.
     *
     * <p>Reached when two requests race to open the next trick: both compute the
     * same sequence number and the loser collides. A 409 and not a 422, because a
     * different state resolves it — the winner's trick is now the open one, and
     * re-reading the session shows a trick to play into.
     *
     * <p>Distinct from {@link #handleAlreadyPlayedInTrick} even though both are
     * conflicts on the same table: no play is involved here, and telling the two
     * apart is the difference between somebody else having opened the trick and the
     * caller having already played into it.
     *
     * @param exception the refusal, carrying the session and the sequence number
     * @return a 409 problem detail
     */
    @ExceptionHandler(TrickAlreadyOpenException.class)
    public ProblemDetail handleTrickAlreadyOpen(final TrickAlreadyOpenException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Trick already open");
        problem.setDetail("Trick " + exception.sequence() + " is already open in this session.");
        return problem;
    }

    /**
     * A seat has already played into this trick.
     *
     * <p>A 409 rather than the 422 that {@link #handleOutOfTurn} returns, because
     * this is not a turn-order mistake: the seat's play is already recorded, so the
     * request lost a race with an identical one rather than arriving in the wrong
     * order. A retry cannot succeed, but re-reading the trick shows the play the
     * caller was trying to make.
     *
     * <p>The detail names the seat and not the trick. A seat number is something
     * every player at the table can already see; the trick identifier is an internal
     * key that would widen the response without helping whoever reads it, on the
     * same reasoning as {@link #handleCardNotInHand}.
     *
     * @param exception the refusal, carrying the trick and the seat
     * @return a 409 problem detail naming only the seat
     */
    @ExceptionHandler(AlreadyPlayedInTrickException.class)
    public ProblemDetail handleAlreadyPlayedInTrick(final AlreadyPlayedInTrickException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Already played in this trick");
        problem.setDetail("Seat " + exception.seatOrder() + " has already played into this trick.");
        return problem;
    }

    /**
     * The card named has already been played into this trick.
     *
     * <p>Separate from {@link #handleAlreadyPlayedInTrick} because the two answer
     * different questions: that one refuses a second play from one seat, this one
     * refuses one card appearing twice in a trick whichever seat played it.
     * Collapsing them would tell a caller that something conflicted without saying
     * whether its own earlier play or somebody else's is the reason.
     *
     * <p>The card identifier is echoed because the caller supplied it.
     *
     * @param exception the refusal, carrying the trick and the card
     * @return a 409 problem detail naming only the card
     */
    @ExceptionHandler(CardAlreadyPlayedException.class)
    public ProblemDetail handleCardAlreadyPlayed(final CardAlreadyPlayedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Card already played");
        problem.setDetail("Card " + exception.cardId() + " has already been played into this trick.");
        return problem;
    }

    /**
     * The play named as the winner was not made into the trick being resolved.
     *
     * <p>A 422 and deliberately not a 409. A conflict entitles the caller to retry
     * once the state has moved on, and no state this application can reach makes a
     * play from one trick the winner of another — so a status that invites a retry
     * would be an invitation to a request that can never succeed.
     *
     * <p>Logged at warning level with both identifiers, because there is no storage
     * constraint behind this check and there is not going to be one: the composite
     * key that would provide it collides with the rule that lets a winning play be
     * deleted (ADR-023). This handler firing is therefore the only evidence that
     * will ever exist, and it means either a defect in resolution or a request that
     * reached the use case naming a play it had no business naming.
     *
     * <p>The response carries neither identifier. The caller cannot act on them, and
     * a trick or play identifier belonging to somebody else's session is exactly the
     * sort of internal key that should not be echoed back to whoever guessed it.
     *
     * @param exception the refusal, carrying the trick and the play
     * @return a 422 problem detail whose detail is a fixed string
     */
    @ExceptionHandler(WinningPlayNotInTrickException.class)
    public ProblemDetail handleWinningPlayNotInTrick(final WinningPlayNotInTrickException exception) {
        LOG.warn("Play {} was named as the winner of trick {}, which it was not played into",
                exception.playId(), exception.trickId(), exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("That play is not in this trick");
        problem.setDetail("The play named as the winner was not made into the trick being resolved.");
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
