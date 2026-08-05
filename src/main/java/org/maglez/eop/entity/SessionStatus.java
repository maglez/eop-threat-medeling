package org.maglez.eop.entity;

/**
 * Where a session is in its life.
 *
 * <p>Stored as the constant name rather than the ordinal, so a database dump is
 * readable and reordering these constants is not a silent data migration.
 *
 * <p>Only {@link #LOBBY} accepts new players, and only {@link #LOBBY} can be
 * started. Every other value is terminal for the purposes of this story:
 * nothing here advances a session out of {@link #IN_PROGRESS}, because playing
 * cards arrives with EOP-14.
 */
public enum SessionStatus {

    /** Players are gathering. Joining is open; play has not begun. */
    LOBBY,
    /** The facilitator has closed the lobby. Joining is refused from here on. */
    IN_PROGRESS,
    /** Every trick has been played and the score is final. */
    COMPLETED,
    /**
     * Given up on rather than finished. No code reaches this state yet: it
     * exists so that expiry and abandonment have somewhere to live when they
     * are implemented, rather than being bolted on as a nullable flag later.
     */
    ABANDONED;

    /**
     * Whether a player may join a session in this state.
     *
     * <p>Expressed as a question about the state rather than an equality check
     * scattered across use cases, so that adding a state forces this method to
     * be reconsidered in one place.
     *
     * @return true only in {@link #LOBBY}
     */
    public boolean acceptsNewPlayers() {
        return this == LOBBY;
    }
}
