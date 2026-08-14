package org.maglez.eop.usecase;

/**
 * The kinds of change a session can announce.
 *
 * <p>Each constant carries the name that appears on the wire. That name is fixed
 * by the published contract in {@code docs/api/openapi.yml}, so it is kept beside
 * the constant rather than in a translation table in the delivery layer, where the
 * two could drift apart without anything failing.
 *
 * <p>Pure domain-adjacent type: no Spring, no Jakarta, no persistence annotations.
 *
 * <p>{@link #HAND_DEALT}, {@link #CARD_PLAYED} and {@link #TRICK_RESOLVED} are
 * declared here and in the contract but nothing publishes them yet; wiring the
 * publisher into the trick-play use cases is a later slice. They are minted now
 * because the wire name belongs to the contract, and a client that matches on a
 * name today must keep working unchanged once the server starts emitting it. A
 * later release may begin emitting one of these names; it may never rename one.
 */
public enum SessionEventType {

    /** A participant was seated in the lobby. */
    PLAYER_JOINED("player-joined"),

    /** The facilitator closed the lobby and play began. */
    GAME_STARTED("game-started"),

    /** The deck was dealt and every seated player now holds a hand. */
    HAND_DEALT("hand-dealt"),

    /** A player added a card to the current trick. */
    CARD_PLAYED("card-played"),

    /** A trick was resolved and the seat that took it is known. */
    TRICK_RESOLVED("trick-resolved");

    private final String wireName;

    SessionEventType(final String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the event name as it appears in the {@code event:} field of a
     * server-sent event frame.
     *
     * @return the wire name, lower case and hyphenated
     */
    public String wireName() {
        return wireName;
    }
}
