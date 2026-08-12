package org.maglez.eop.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One time around the table: each participating player lays a single card face up, and the highest
 * card of the suit that was led takes the trick unless somebody played trump.
 *
 * <p>A trick is immutable. Every rule here answers a question and returns a new instance rather than
 * mutating this one, so a rejected play leaves no trace and a resolved trick cannot be quietly
 * re-resolved.
 *
 * <p>Deliberately <em>not</em> a fixed number of plays. Because EOP-14 deals the whole deck and hands
 * off the remainder to the lowest seats (ADR-023), at four and five players the final trick is short:
 * some seats have already run out of cards. A trick is therefore one card from each seat that still
 * held a card when it opened, which is why {@link #seatToPlay(Collection)} and
 * {@link #isComplete(Collection)} both need to be told which seats still hold cards rather than
 * counting to the table size.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations. The persistence adapter
 * holds its own separate mapped type.
 */
public final class Trick {

    private final UUID trickId;
    private final int sequence;
    private final int leaderSeat;
    private final List<TrickPlay> plays;
    private final TrickPlay winner;

    private Trick(
            final UUID trickId,
            final int sequence,
            final int leaderSeat,
            final List<TrickPlay> plays,
            final TrickPlay winner) {
        this.trickId = Objects.requireNonNull(trickId, "trickId is required");
        this.plays = List.copyOf(Objects.requireNonNull(plays, "plays is required"));
        this.winner = winner;

        if (sequence < 1) {
            throw new IllegalArgumentException("A trick sequence is one-based, so it cannot be " + sequence);
        }
        if (leaderSeat < 0 || leaderSeat >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    "A leader seat must be between 0 and "
                            + (GameSession.MAXIMUM_PLAYERS - 1)
                            + " inclusive, so it cannot be "
                            + leaderSeat);
        }
        this.sequence = sequence;
        this.leaderSeat = leaderSeat;

        final long distinctSeats = this.plays.stream().map(TrickPlay::seatOrder).distinct().count();
        if (distinctSeats != this.plays.size()) {
            throw new IllegalArgumentException("A seat cannot play twice in the same trick");
        }
        final long distinctCards =
                this.plays.stream().map(play -> play.card().cardId()).distinct().count();
        if (distinctCards != this.plays.size()) {
            throw new IllegalArgumentException("The same card cannot be played twice in the same trick");
        }
        if (winner != null && !this.plays.contains(winner)) {
            throw new IllegalArgumentException("The winning play must be one of the plays in the trick");
        }
        if (!this.plays.isEmpty() && this.plays.get(0).seatOrder() != leaderSeat) {
            throw new IllegalArgumentException(
                    "The first play in a trick belongs to the leading seat "
                            + leaderSeat
                            + ", not to seat "
                            + this.plays.get(0).seatOrder());
        }
    }

    /**
     * Opens an empty trick for the given leading seat.
     *
     * <p>The leading seat of the first trick is derived from the deal rather than chosen: it is the
     * seat holding the lowest-ranked Tampering card actually dealt (see {@link Hands#openingLeaderSeat()}).
     * The leading seat of every later trick is the seat that took the previous one.
     *
     * @param trickId the identifier for the new trick, minted by the caller (ADR-018)
     * @param sequence the one-based position of this trick within the session
     * @param leaderSeat the seat that plays first and whose card sets the led suit
     * @return an open trick with no plays and no winner
     */
    public static Trick open(final UUID trickId, final int sequence, final int leaderSeat) {
        return new Trick(trickId, sequence, leaderSeat, List.of(), null);
    }

    /**
     * Rebuilds a trick from stored state.
     *
     * <p>This is the only path used when a client reconnects or refreshes, because the database is the
     * only authority on where a game has got to (ADR-014). The invariants in the constructor run
     * against stored rows as well as against fresh ones, so a row set that could not have arisen from
     * legal play fails loudly here rather than producing a game in an impossible state.
     *
     * @param trickId the stored identifier
     * @param sequence the stored one-based position within the session
     * @param leaderSeat the stored leading seat
     * @param plays the stored plays, in play order
     * @param winner the stored winning play, or {@code null} if the trick is not yet resolved
     * @return the reconstituted trick
     */
    public static Trick reconstitute(
            final UUID trickId,
            final int sequence,
            final int leaderSeat,
            final List<TrickPlay> plays,
            final TrickPlay winner) {
        return new Trick(trickId, sequence, leaderSeat, plays, winner);
    }

    /**
     * The suit that was led, which is the suit of the first card played.
     *
     * @return the led suit, or empty while the trick has no plays
     */
    public Optional<StrideCategory> ledSuit() {
        return plays.isEmpty() ? Optional.empty() : Optional.of(plays.get(0).card().suit());
    }

    /**
     * Checks that a card may legally be played into this trick from the given hand, and does nothing
     * if it may.
     *
     * <p>The whole follow-suit rule lives here, in four branches:
     *
     * <ol>
     *   <li>The hand must actually hold the card. This is the check that stops a card being played
     *       twice, and stops a player playing a card dealt to somebody else.
     *   <li>If no suit has been led yet, the leader may play anything at all.
     *   <li>A card of the led suit is always legal.
     *   <li>Otherwise the play is legal only if the hand holds no card of the led suit. A player who
     *       can follow suit must.
     * </ol>
     *
     * <p>Note what the last branch permits: a player holding none of the led suit may play
     * <em>any</em> card, including a trump. That is how trump gets into a trick, and it is deliberate
     * rather than an oversight.
     *
     * <p>This check is the server's, not the client's. A user interface that only offers legal cards
     * is a courtesy; the cards a client is willing to offer say nothing about the request a client is
     * able to send.
     *
     * @param card the card the player is attempting to play
     * @param hand the hand the player actually holds
     * @throws CardNotInHandException if the hand does not hold the card
     * @throws MustFollowSuitException if the hand holds the led suit and the card is of another suit
     */
    public void assertLegalPlay(final Card card, final Hand hand) {
        Objects.requireNonNull(card, "card is required");
        Objects.requireNonNull(hand, "hand is required");

        if (!hand.holds(card)) {
            throw new CardNotInHandException(hand.handId(), card.cardId());
        }

        final Optional<StrideCategory> led = ledSuit();
        if (led.isEmpty()) {
            return;
        }

        final StrideCategory ledSuit = led.get();
        if (card.suit() == ledSuit) {
            return;
        }
        if (hand.holdsSuit(ledSuit)) {
            throw new MustFollowSuitException(ledSuit, card.suit());
        }
    }

    /**
     * Checks that the given seat is the seat whose turn it is, and does nothing if it is.
     *
     * <p>Like the follow-suit check, this belongs on the server, and the seat passed in must have been
     * derived from the credential the request presented rather than from a player identifier the
     * request supplied. Otherwise a caller could play on another player's behalf simply by naming them.
     *
     * @param seatOrder the seat attempting to play
     * @param seatsHoldingCards the seats that still hold at least one card
     * @throws OutOfTurnException if it is another seat's turn
     * @throws IllegalStateException if the trick is already complete
     */
    public void assertSeatMayPlay(final int seatOrder, final Collection<Integer> seatsHoldingCards) {
        final OptionalInt expected = seatToPlay(seatsHoldingCards);
        if (expected.isEmpty()) {
            throw new IllegalStateException(
                    describe() + " is complete, so seat " + seatOrder + " cannot play into it");
        }
        if (expected.getAsInt() != seatOrder) {
            throw new OutOfTurnException(expected.getAsInt(), seatOrder);
        }
    }

    /**
     * Works out which seat plays next.
     *
     * <p>Play is clockwise, which is ascending seat order wrapping back to zero (ADR-019). The naive
     * form of this rule — leader's seat plus the number of cards already played — is correct only
     * while every seat still holds a card, and ADR-023 makes that false for the last trick at four and
     * five players. The general form implemented here is <em>the next seat clockwise from the last
     * play that has not yet played in this trick and still holds a card</em>.
     *
     * <p>Seats that do not exist at this table are simply absent from {@code seatsHoldingCards} and so
     * are skipped by the same test that skips seats which have run out of cards.
     *
     * @param seatsHoldingCards the seats that still hold at least one card
     * @return the seat that plays next, or empty if every eligible seat has played
     */
    public OptionalInt seatToPlay(final Collection<Integer> seatsHoldingCards) {
        Objects.requireNonNull(seatsHoldingCards, "seatsHoldingCards is required");

        if (plays.isEmpty()) {
            return seatsHoldingCards.contains(leaderSeat)
                    ? OptionalInt.of(leaderSeat)
                    : OptionalInt.empty();
        }

        final int from = plays.get(plays.size() - 1).seatOrder();
        for (int step = 1; step <= GameSession.MAXIMUM_PLAYERS; step++) {
            final int candidate = (from + step) % GameSession.MAXIMUM_PLAYERS;
            if (seatsHoldingCards.contains(candidate) && !hasPlayed(candidate)) {
                return OptionalInt.of(candidate);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Whether every seat eligible to play in this trick has played.
     *
     * @param seatsHoldingCards the seats that still hold at least one card
     * @return true when no seat is left to play
     */
    public boolean isComplete(final Collection<Integer> seatsHoldingCards) {
        return !plays.isEmpty() && seatToPlay(seatsHoldingCards).isEmpty();
    }

    /**
     * Whether the given seat has already played into this trick.
     *
     * @param seatOrder the seat to test
     * @return true if that seat has a play in this trick
     */
    public boolean hasPlayed(final int seatOrder) {
        return plays.stream().anyMatch(play -> play.seatOrder() == seatOrder);
    }

    /**
     * Adds a play to the trick, returning the new trick.
     *
     * <p>Legality is not re-checked here: {@link #assertLegalPlay(Card, Hand)} and
     * {@link #assertSeatMayPlay(int, Collection)} answer those questions and need the player's hand
     * and the seats still holding cards to do it, neither of which a trick knows. What this method
     * does enforce, through the constructor, is the pair of invariants that hold regardless of any
     * hand: one play per seat, and one play per card.
     *
     * @param play the play to add
     * @return a new trick with the play appended
     * @throws IllegalStateException if the trick has already been resolved
     */
    public Trick play(final TrickPlay play) {
        Objects.requireNonNull(play, "play is required");
        if (winner != null) {
            throw new IllegalStateException(
                    describe() + " has already been resolved, so no further card can be played into it");
        }
        final List<TrickPlay> extended = new ArrayList<>(plays);
        extended.add(play);
        return new Trick(trickId, sequence, leaderSeat, extended, null);
    }

    /**
     * Resolves the trick, returning a new trick that records which play took it.
     *
     * <p>The rule: the highest card of the led suit takes the trick, unless one or more Elevation of
     * Privilege cards were played, in which case the highest of those takes it. Only a card of the led
     * suit or a trump can take a trick, so every other card in the trick is irrelevant to the outcome
     * — it was played to get rid of it, or to say something about the system, and both are legitimate.
     *
     * <p>This is correct when Elevation of Privilege is itself the led suit: the decisive suit is then
     * trump either way and the highest card of it wins, which is the same answer.
     *
     * <p>There can be no tie. The deck holds no two cards of the same suit and rank, so within the
     * decisive suit exactly one play holds the highest rank.
     *
     * @return a new trick with the winning play recorded
     * @throws IllegalStateException if the trick has no plays, or has already been resolved
     */
    public Trick resolved() {
        if (plays.isEmpty()) {
            throw new IllegalStateException(describe() + " has no plays, so it cannot be resolved");
        }
        if (winner != null) {
            throw new IllegalStateException(describe() + " has already been resolved");
        }
        return new Trick(trickId, sequence, leaderSeat, plays, winningPlay());
    }

    private TrickPlay winningPlay() {
        final StrideCategory led = plays.get(0).card().suit();
        final boolean anyTrump = plays.stream().anyMatch(play -> play.card().isTrump());
        final StrideCategory decisive = anyTrump ? StrideCategory.ELEVATION_OF_PRIVILEGE : led;

        return plays.stream()
                .filter(play -> play.card().suit() == decisive)
                .reduce((held, candidate) -> candidate.card().rank().beats(held.card().rank()) ? candidate : held)
                // Unreachable: the leader's own card is of the led suit, so if trump is not the
                // decisive suit there is at least one candidate, and if it is then some play was trump.
                .orElseThrow(() -> new IllegalStateException(
                        describe() + " contains no card of the decisive suit " + decisive));
    }

    private String describe() {
        return "Trick " + sequence;
    }

    /**
     * The play that took this trick.
     *
     * @return the winning play, or empty while the trick is unresolved
     */
    public Optional<TrickPlay> winner() {
        return Optional.ofNullable(winner);
    }

    /**
     * The seat that leads the next trick, which is the seat that took this one.
     *
     * @return the seat that leads next
     * @throws IllegalStateException if this trick has not been resolved
     */
    public int nextLeaderSeat() {
        if (winner == null) {
            throw new IllegalStateException(
                    describe() + " is unresolved, so the seat that leads next is not yet known");
        }
        return winner.seatOrder();
    }

    /**
     * The identifier of this trick.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }

    /**
     * The one-based position of this trick within the session.
     *
     * @return the trick sequence
     */
    public int sequence() {
        return sequence;
    }

    /**
     * The seat that played first and set the led suit.
     *
     * @return the leading seat
     */
    public int leaderSeat() {
        return leaderSeat;
    }

    /**
     * The plays in this trick, in play order.
     *
     * @return an unmodifiable list of plays
     */
    public List<TrickPlay> plays() {
        return plays;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Trick that)) {
            return false;
        }
        return sequence == that.sequence
                && leaderSeat == that.leaderSeat
                && trickId.equals(that.trickId)
                && plays.equals(that.plays)
                && Objects.equals(winner, that.winner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trickId, sequence, leaderSeat, plays, winner);
    }

    /**
     * A summary that names no card.
     *
     * <p>Cards already played are public information, but a trick in progress is interpolated into log
     * lines alongside hands that are not, and a rendering that lists cards here invites the same
     * rendering on {@link Hand}. The count and the led suit are what a diagnostic actually needs.
     *
     * @return a short description of the trick
     */
    @Override
    public String toString() {
        return "Trick[sequence="
                + sequence
                + ", leaderSeat="
                + leaderSeat
                + ", ledSuit="
                + ledSuit().map(Enum::name).orElse("none")
                + ", plays="
                + plays.size()
                + ", resolved="
                + (winner != null)
                + "]";
    }
}
