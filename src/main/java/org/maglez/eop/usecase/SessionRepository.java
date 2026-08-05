package org.maglez.eop.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.JoinCode;
import org.maglez.eop.entity.JoinCodeUnavailableException;
import org.maglez.eop.entity.Player;
import org.maglez.eop.entity.SeatAlreadyTakenException;
import org.maglez.eop.entity.SessionNotJoinableException;

/**
 * Port through which the application reads and writes game sessions.
 *
 * <p>The read methods return a fully reconstituted aggregate built from database
 * rows and from nothing else. No in-memory registry, cache or subscriber list
 * contributes to the answer, which is what makes a reconnect after a deployment
 * indistinguishable from a first request (ADR-014).
 *
 * <p>The write methods are deliberately narrow rather than a single {@code save}.
 * Each one is a single transaction and each one is <em>conditional on the state
 * the caller observed</em>: a write that would apply to a session someone else has
 * already moved on is rejected rather than silently applied. That is a
 * compare-and-swap, not a duplicated business rule — the rules themselves live in
 * {@link GameSession}, which the caller has already consulted before arriving
 * here. Without the condition there is a window in which a join lands after play
 * has started, producing a seated player that the deal never saw.
 */
public interface SessionRepository {

    /**
     * Reads a session and all of its players by identifier.
     *
     * @param sessionId the identifier to look up
     * @return the session, or empty if no session has that identifier
     */
    Optional<GameSession> findById(UUID sessionId);

    /**
     * Reads a session and all of its players by join code.
     *
     * <p>The caller is expected to treat an empty result and an unparseable code
     * identically, so that this endpoint cannot be used to confirm which codes
     * are real.
     *
     * @param joinCode the canonical join code to look up
     * @return the session, or empty if no session has that code
     */
    Optional<GameSession> findByJoinCode(JoinCode joinCode);

    /**
     * Inserts a new session together with its facilitator.
     *
     * <p>Both rows are written in one transaction. A session that existed without
     * its facilitator would be reachable by join code and impossible to start.
     *
     * @param session a freshly opened lobby holding exactly one player
     * @throws JoinCodeUnavailableException if the join code is already taken, in
     *     which case the caller draws another code and tries again
     */
    void createLobby(GameSession session);

    /**
     * Inserts one player into an existing session.
     *
     * @param sessionId the session to seat the player in
     * @param player the player to seat, carrying the seat order it is claiming
     * @param occurredAt the instant recorded as the session's last update
     * @throws SessionNotJoinableException if the session is no longer accepting
     *     players, which includes the case where play started between the
     *     caller's read and this write
     * @throws SeatAlreadyTakenException if another join claimed that seat first,
     *     in which case the caller re-reads and claims the next one
     */
    void seatPlayer(UUID sessionId, Player player, Instant occurredAt);

    /**
     * Moves a session out of its lobby and into play.
     *
     * @param sessionId the session to start
     * @param occurredAt the instant recorded as the session's last update
     * @throws SessionNotJoinableException if the session had already left the
     *     lobby, which is how two facilitators clicking at once is resolved
     */
    void recordStarted(UUID sessionId, Instant occurredAt);
}
