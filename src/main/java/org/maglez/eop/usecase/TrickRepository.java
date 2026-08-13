package org.maglez.eop.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.AlreadyPlayedInTrickException;
import org.maglez.eop.entity.CardAlreadyPlayedException;
import org.maglez.eop.entity.CardNotInHandException;
import org.maglez.eop.entity.NotYourSeatException;
import org.maglez.eop.entity.PlayerNotInSessionException;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionNotJoinableException;
import org.maglez.eop.entity.Trick;
import org.maglez.eop.entity.TrickAlreadyOpenException;
import org.maglez.eop.entity.TrickPlay;

/**
 * Port through which the application reads and writes tricks and the plays made into
 * them.
 *
 * <p>As with {@link HandRepository}, a session identifier leads every signature because
 * {@link Trick} does not carry one — it holds an identifier, a sequence, a leader seat,
 * its plays and its winner, and nothing else.
 *
 * <p>Every write takes an {@code expectedLeaderSeat}. That is the compare-and-set
 * witness, and it is the whole turn-order guard: the statement names the leader seat the
 * caller's snapshot showed, and a snapshot taken before somebody else moved matches
 * nothing. The value is always derived — from the trick the caller read, or from
 * {@link HandRepository#findCurrentLeaderSeat} — and is never taken from a request body.
 * ADR-020 is explicit that serialisation rests on the row lock the conditional statement
 * takes rather than on the value it returns, and that {@code @Version} is mapped but is
 * deliberately not the enforcement mechanism.
 *
 * <p>The recorded leader seat advances <em>once per trick</em>, not once per play. A play
 * reads it and locks the row without changing it; a resolution moves it on. Whose turn it
 * is within a trick is derived by {@code Trick} from the plays already made, because the
 * trick's own plays already determine it and a stored per-play pointer would be a second
 * authority on a fact that is already written down.
 *
 * <p>Appending a play is one method rather than two on purpose. It removes the card from
 * a hand and records the play, and those cannot be separate calls: two transactions would
 * admit a card gone from a hand with no play recorded, or a play recorded for a card the
 * player still holds, and a single transaction spanning two port calls would put
 * {@code org.springframework.transaction} into this package.
 *
 * <p><strong>Authorising the requester is the caller's obligation, not this port's.</strong>
 * Nothing here takes an acting player, so no implementation can check one. {@code
 * appendPlay} is the single exception and only partly: the play it is handed names a
 * player and a seat, and the implementation asserts those agree with the session's
 * seating — a check on the play, not on the requester. {@code openTrick} and {@code
 * recordResolution} carry no player at all, so for them there is no check to make here
 * and the use case is the only place one can happen. {@code expectedLeaderSeat} is a
 * turn-order witness and must never be read from a request body; it is not a substitute
 * for membership, since a stranger who guesses it correctly still passes it.
 */
public interface TrickRepository {

    /**
     * Reads the trick most recently opened in a session.
     *
     * <p>The result may be resolved or unresolved; the caller asks the trick itself.
     * Empty means no trick has been opened, which is the state of a session between the
     * deal and the first lead.
     *
     * @param sessionId the session whose current trick to read
     * @return the highest-numbered trick of that session, or empty if it has none
     */
    Optional<Trick> findCurrentTrick(UUID sessionId);

    /**
     * Opens a new trick.
     *
     * @param sessionId the session the trick belongs to
     * @param trick a freshly opened trick, holding no plays
     * @param expectedLeaderSeat the leader seat the caller's snapshot showed
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is not in play
     * @throws TrickAlreadyOpenException if a trick with that sequence is already open in
     *     the session, which is how two requests opening the same trick are resolved
     */
    void openTrick(UUID sessionId, Trick trick, int expectedLeaderSeat, Instant occurredAt);

    /**
     * Records one play into an open trick and takes the card out of the player's hand.
     *
     * <p>There is no {@code occurredAt} parameter. A {@link TrickPlay} carries its own
     * {@code playedAt}, which is the value the row stores, and a second timestamp
     * argument would be a second authority on one fact.
     *
     * @param sessionId the session the trick belongs to
     * @param trickId the trick to play into
     * @param expectedLeaderSeat the leader seat the caller's snapshot showed
     * @param play the play to record, carrying the seat, the card and the instant
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is not in play
     * @throws PlayerNotInSessionException if the play names a player who does not sit in
     *     that session
     * @throws NotYourSeatException if the play names a seat its player does not occupy
     * @throws CardNotInHandException if the player's hand does not hold the card, which
     *     includes the case where another request played it first
     * @throws AlreadyPlayedInTrickException if that seat has already played into the
     *     trick
     * @throws CardAlreadyPlayedException if that card has already been played into the
     *     trick
     */
    void appendPlay(UUID sessionId, UUID trickId, int expectedLeaderSeat, TrickPlay play);

    /**
     * Records which play took a trick and moves the leader on to the next one.
     *
     * <p>The next leader is a parameter rather than something derived here, because
     * deriving it needs the seats that still hold cards — a session-wide fact this port
     * has no business computing, and one the domain already answers.
     *
     * @param sessionId the session the trick belongs to
     * @param resolved the trick, carrying its winning play
     * @param expectedLeaderSeat the leader seat the caller's snapshot showed
     * @param nextLeaderSeat the seat that leads the next trick
     * @param occurredAt the instant recorded as the session's last update
     * @throws SessionNotFoundException if the session no longer exists
     * @throws SessionNotJoinableException if the session is not in play, which includes
     *     the case where another request resolved this trick first
     */
    void recordResolution(
            UUID sessionId, Trick resolved, int expectedLeaderSeat, int nextLeaderSeat, Instant occurredAt);
}
