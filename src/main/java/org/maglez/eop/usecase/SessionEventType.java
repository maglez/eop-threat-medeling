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
 */
public enum SessionEventType {

    /** A participant was seated in the lobby. */
    PLAYER_JOINED("player-joined"),

    /** The facilitator closed the lobby and play began. */
    GAME_STARTED("game-started");

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
