package org.maglez.eop.entity;

/**
 * The rank printed on a threat card.
 *
 * <p>Rank does double duty in Elevation of Privilege. It decides which card
 * takes a trick, and it encodes a rough ordering of how commonly the threat is
 * encountered, how much impact it has and how easy it is to exploit — higher
 * cards carry the threats the deck's authors considered more significant.
 *
 * <p>The ace is high. That matters because aces are Open Threat cards: a player
 * must name a threat that appears on no other card, and the ace beating
 * everything else in its suit is what makes that worth attempting.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations.
 */
public enum Rank {

    /** Two — the lowest rank. */
    TWO(2, "2"),
    /** Three. */
    THREE(3, "3"),
    /** Four. */
    FOUR(4, "4"),
    /** Five. */
    FIVE(5, "5"),
    /** Six. */
    SIX(6, "6"),
    /** Seven. */
    SEVEN(7, "7"),
    /** Eight. */
    EIGHT(8, "8"),
    /** Nine. */
    NINE(9, "9"),
    /** Ten. */
    TEN(10, "10"),
    /** Jack. */
    JACK(11, "J"),
    /** Queen. */
    QUEEN(12, "Q"),
    /** King. */
    KING(13, "K"),
    /** Ace — the highest rank, and an Open Threat card. */
    ACE(14, "A");

    private final int value;
    private final String symbol;

    Rank(final int rankValue, final String rankSymbol) {
        this.value = rankValue;
        this.symbol = rankSymbol;
    }

    /**
     * Resolves a rank from its numeric value.
     *
     * @param value the numeric value, 2 through 14
     * @return the matching rank
     * @throws IllegalArgumentException if no rank has that value
     */
    public static Rank ofValue(final int value) {
        for (final Rank rank : values()) {
            if (rank.value == value) {
                return rank;
            }
        }
        throw new IllegalArgumentException("No card rank has the value " + value);
    }

    /**
     * The numeric value used to compare ranks. The ace is high, so 14.
     *
     * @return the numeric value
     */
    public int value() {
        return value;
    }

    /**
     * How the rank is printed on the card face.
     *
     * @return the printed symbol
     */
    public String symbol() {
        return symbol;
    }

    /**
     * Whether this rank beats another. Comparison is only meaningful between
     * cards of the same suit, or between trump cards.
     *
     * @param other the rank to compare against
     * @return true if this rank is strictly higher
     */
    public boolean beats(final Rank other) {
        return this.value > other.value;
    }
}
