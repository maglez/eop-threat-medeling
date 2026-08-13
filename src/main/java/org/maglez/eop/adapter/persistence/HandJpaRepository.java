package org.maglez.eop.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the hand table.
 *
 * <p>Package private, and not the application's port. Hands are always read for a
 * whole session at once, and that is a correctness requirement rather than a
 * convenience: {@code Hands.reconstitute} re-runs every cross-seat invariant it was
 * given, including the rule that one card cannot sit in two hands, and that rule has
 * no database constraint behind it. {@code pk_hand_card} stops a card appearing twice
 * in one hand and {@code uq_trick_play_trick_card} stops it being played twice into
 * one trick, but nothing scopes a card to a single hand within a session. Reading one
 * seat at a time would satisfy the invariant vacuously and thereby switch off the only
 * enforcement it has.
 *
 * <p>Reading in seat order matters for the same reason it matters on the player table:
 * the order seats are read in is the order play moves around the table, and sorting
 * later would put the sort in a second place.
 *
 * <p>There is no method that finds a hand by player. A player's hand is reached through
 * the session's hands, so that a hand is never read without the table it belongs to.
 */
interface HandJpaRepository extends JpaRepository<HandJpaEntity, UUID> {

    /**
     * Reads every hand dealt in a session, in the order play moves around the table.
     *
     * @param gameSessionId the session whose hands to read
     * @return the dealt hands, ascending by seat order, empty if nothing has been dealt
     */
    List<HandJpaEntity> findByGameSessionIdOrderBySeatOrderAsc(UUID gameSessionId);
}
