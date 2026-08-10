package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import org.maglez.eop.entity.ConnectionStatus;
import org.maglez.eop.entity.DisplayName;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.IdentityTokenHash;
import org.maglez.eop.entity.JoinCode;
import org.maglez.eop.entity.Player;
import org.maglez.eop.entity.PlayerRole;
import org.maglez.eop.entity.SeatAlreadyTakenException;
import org.maglez.eop.entity.SessionFullException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.UnknownJoinCodeException;

/**
 * Seats a new participant in an existing lobby.
 *
 * <p>Seat order is assigned here, once, and is never recomputed afterwards. Play
 * runs clockwise, so the seat a player is given determines who follows them for the
 * rest of the game; deriving it later from a database sort or a join timestamp would
 * let a disconnect move a player around the table (ADR-019).
 *
 * <p>Two players can ask for the same seat at the same moment. That race is settled
 * by {@code uq_player_session_seat} rather than by any check performed here: the
 * loser is told the seat was taken, re-reads the session, and claims the next one.
 */
public class JoinSessionUseCase {

    /**
     * How many times a contested seat is re-claimed before giving up.
     *
     * <p>A table holds six players, so a single joiner can lose the race for a seat
     * at most five times before the table is full and the attempt fails for a
     * different and more informative reason. Eight leaves room for that without
     * being unbounded.
     */
    private static final int MAXIMUM_SEAT_ATTEMPTS = 8;

    private final SessionRepository sessionRepository;
    private final IdentifierGenerator identifierGenerator;
    private final IdentityTokenGenerator identityTokenGenerator;
    private final JoinAttemptLimiter joinAttemptLimiter;
    private final SessionEventPublisher sessionEventPublisher;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to read the session and insert the player
     * @param identifierGenerator the port supplying the player identifier
     * @param identityTokenGenerator the port supplying the identity token
     * @param joinAttemptLimiter the port that throttles failed join attempts
     * @param sessionEventPublisher the port that announces the new player
     * @param clock the clock used to stamp the join, injected so tests are deterministic
     */
    public JoinSessionUseCase(
            final SessionRepository sessionRepository,
            final IdentifierGenerator identifierGenerator,
            final IdentityTokenGenerator identityTokenGenerator,
            final JoinAttemptLimiter joinAttemptLimiter,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.identifierGenerator = Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.identityTokenGenerator =
                Objects.requireNonNull(identityTokenGenerator, "identityTokenGenerator is required");
        this.joinAttemptLimiter = Objects.requireNonNull(joinAttemptLimiter, "joinAttemptLimiter is required");
        this.sessionEventPublisher = Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Adds a participant to the lobby identified by a join code.
     *
     * <p>A code that could never be valid and a code that simply matches nothing both
     * end here as the same exception, recorded as the same kind of failure. Telling
     * those two apart would turn the endpoint into an oracle that confirms which codes
     * are real (ADR-019).
     *
     * <p>A refusal that arrives <em>after</em> the code matched is charged to the
     * throttle for the same reason. A full table and a game already under way are both
     * proof that the code is live, so leaving those paths free would let a caller
     * confirm real codes at full request rate while the limiter — the primary control
     * behind a thirty-bit code space — never fires. Losing a race for a seat is not
     * charged: that is our own contention, not a failed attempt by the caller.
     *
     * <p>The identity token and the player identifier are generated once, before the
     * retry loop, so that losing a race for a seat does not change who the player is.
     *
     * @param rawJoinCode the code as the player typed it, in any case, possibly with
     *     characters that Crockford base32 folds
     * @param displayName the name the player chose, already validated
     * @param clientAddress the caller's address, used only to attribute failed attempts
     * @return the session as it stands after the join, with the player's credential
     * @throws NullPointerException if displayName is null
     * @throws TooManyJoinAttemptsException if this caller has failed too often
     * @throws UnknownJoinCodeException if the code matches no session
     * @throws SessionNotJoinableException if play has started
     * @throws SessionFullException if the table is full
     * @throws SeatAlreadyTakenException if every seat claim lost its race
     */
    public SessionAdmission execute(final String rawJoinCode, final DisplayName displayName, final String clientAddress) {
        Objects.requireNonNull(displayName, "displayName is required");

        joinAttemptLimiter.checkAllowed(clientAddress, rawJoinCode);

        final var parsedCode = JoinCode.parse(rawJoinCode);
        if (parsedCode.isEmpty()) {
            joinAttemptLimiter.recordFailure(clientAddress, rawJoinCode);
            throw new UnknownJoinCodeException();
        }

        final var now = clock.instant();
        final var playerId = identifierGenerator.nextIdentifier();
        final var plaintextToken =
                identityTokenGenerator.nextToken();
        final var tokenHash =
                IdentityTokenHash.of(plaintextToken);

        SeatAlreadyTakenException lastContest = null;
        for (int attempt = 0; attempt < MAXIMUM_SEAT_ATTEMPTS; attempt++) {
            final var session = sessionRepository.findByJoinCode(parsedCode.get()).orElse(null);
            if (session == null) {
                joinAttemptLimiter.recordFailure(clientAddress, rawJoinCode);
                throw new UnknownJoinCodeException();
            }

            final Player joining;
            final GameSession joined;
            try {
                joining = new Player(
                        playerId,
                        displayName,
                        session.nextSeatOrder(),
                        PlayerRole.PARTICIPANT,
                        ConnectionStatus.CONNECTED,
                        tokenHash,
                        now);
                joined = session.join(joining, now);
            }
            catch (final SessionFullException | SessionNotJoinableException refused) {
                joinAttemptLimiter.recordFailure(clientAddress, rawJoinCode);
                throw refused;
            }

            try {
                sessionRepository.seatPlayer(session.sessionId(), joining, now);
            }
            catch (final SeatAlreadyTakenException contested) {
                lastContest = contested;
                continue;
            }

            sessionEventPublisher.publish(
                    new SessionEvent(SessionEventType.PLAYER_JOINED, joined.sessionId(), now));
            return new SessionAdmission(joined, playerId, plaintextToken);
        }
        throw Objects.requireNonNull(lastContest, "a seat contest must have occurred");
    }
}
