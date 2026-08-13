package org.maglez.eop.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the trick table.
 *
 * <p>Package private, and not the application's port. There is one read, and it asks for
 * the highest-numbered trick in a session rather than for an unresolved one. Whether a
 * trick is finished is a question the trick answers about itself once it has been
 * reconstituted, and a query that filtered on {@code winner_play_id IS NULL} would be a
 * second authority on the same fact — one that would silently disagree the moment the
 * domain's idea of complete changed.
 *
 * <p>Recording a winner is a conditional update rather than a save, for the same reason
 * the session's status transitions are. Writing the winner only where none is recorded
 * yet makes a second resolution of the same trick change no rows instead of overwriting
 * the first, and the row count is the whole protocol.
 */
interface TrickJpaRepository extends JpaRepository<TrickJpaEntity, UUID> {

    /**
     * Reads the most recent trick in a session, resolved or not.
     *
     * @param gameSessionId the session whose current trick to read
     * @return the highest-numbered trick, or empty if no trick has been opened
     */
    Optional<TrickJpaEntity> findFirstByGameSessionIdOrderBySequenceDesc(UUID gameSessionId);

    /**
     * Records the winning play of a trick, if that trick has no winner yet.
     *
     * @param trickId the trick to resolve
     * @param winnerPlayId the play that took the trick
     * @return {@code 1} if the winner was recorded, {@code 0} if the trick was already resolved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TrickJpaEntity t SET t.winnerPlayId = :winnerPlayId "
            + "WHERE t.id = :trickId AND t.winnerPlayId IS NULL")
    int recordWinner(@Param("trickId") UUID trickId, @Param("winnerPlayId") UUID winnerPlayId);
}
