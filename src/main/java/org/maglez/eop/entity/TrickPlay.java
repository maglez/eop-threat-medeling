package org.maglez.eop.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One player's card in one trick, together with what they said about it.
 *
 * <p>Immutable. A play is a historical fact: once made it is never edited, which
 * is what lets the trick's winner be recomputed from stored rows rather than
 * trusted from a cached result.
 *
 * <p>Carries the whole {@link Card} rather than a card identifier so that
 * deciding who took the trick needs no lookup. Cards are seeded reference data
 * that gameplay never mutates (PRD §5), so an embedded copy cannot go stale.
 *
 * <p>Carries {@code seatOrder} as well as {@code playerId}. That is a copy of a
 * fact {@link Player} also holds, accepted deliberately: a seat is assigned once
 * at join and never re-derived (ADR-019), so the two cannot drift, and a trick
 * needs the seat to work out who leads next without loading the session's
 * players.
 *
 * <p>Carries no {@link Player}. A player holds the digest of its identity token,
 * and that value has no business travelling inside gameplay data.
 *
 * @param trickPlayId  the play's identifier
 * @param playerId     the player who made the play
 * @param seatOrder    the seat that player occupies
 * @param card         the card played
 * @param threatLinked whether the player connected the threat to the system
 * @param components   the components the player named, possibly empty
 * @param notes        what the player said about the threat, or null
 * @param playedAt     when the play was made
 */
public record TrickPlay(
        UUID trickPlayId,
        UUID playerId,
        int seatOrder,
        Card card,
        boolean threatLinked,
        List<String> components,
        String notes,
        Instant playedAt) {

    /** Most components one play may name. A bound, not a game rule. */
    public static final int MAX_COMPONENTS = 20;

    /** Longest component name accepted. */
    public static final int MAX_COMPONENT_NAME_LENGTH = 200;

    /** Longest note accepted against a single play. */
    public static final int MAX_NOTES_LENGTH = 2000;

    /**
     * Rejects a malformed play at construction, and normalises the free text.
     *
     * <p>{@code threatLinked} being false is a legal, ordinary outcome, not an
     * error: a player who cannot connect their card to the system still plays it,
     * the card still competes for the trick, and the play simply scores nothing
     * (PRD §3.3). Modelling that as a failure or as a skipped turn would change
     * the rules of the game.
     *
     * <p>Components are not required even when the threat was linked. No rule
     * says they must be, and a facilitation tool that refuses a play in a live
     * session because a text box is empty gets in the way of the conversation it
     * exists to support. Scoring (EOP-15) decides what it does with a linked
     * threat that names nothing.
     *
     * <p>Both free-text fields are bounded in length and count because they cross
     * a system boundary from a client and are written to the database.
     *
     * @throws NullPointerException     if a required component is null
     * @throws IllegalArgumentException if the seat is out of range, or the text is over-long
     */
    public TrickPlay {
        Objects.requireNonNull(trickPlayId, "trickPlayId is required");
        Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(card, "card is required");
        Objects.requireNonNull(playedAt, "playedAt is required");
        Objects.requireNonNull(components, "components is required, though it may be empty");
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    "seatOrder must be between 0 and " + (GameSession.MAXIMUM_PLAYERS - 1) + ", was " + seatOrder);
        }
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException(
                    "A play may name at most " + MAX_COMPONENTS + " components, was " + components.size());
        }
        components = components.stream()
                .map(component -> Objects.requireNonNull(component, "a component name must not be null"))
                .map(String::strip)
                .peek(component -> {
                    if (component.isEmpty()) {
                        throw new IllegalArgumentException("A component name must not be blank");
                    }
                    if (component.length() > MAX_COMPONENT_NAME_LENGTH) {
                        throw new IllegalArgumentException("A component name must be at most "
                                + MAX_COMPONENT_NAME_LENGTH + " characters, was " + component.length());
                    }
                })
                .toList();
        if (notes != null) {
            notes = notes.strip();
            if (notes.length() > MAX_NOTES_LENGTH) {
                throw new IllegalArgumentException(
                        "notes must be at most " + MAX_NOTES_LENGTH + " characters, was " + notes.length());
            }
            if (notes.isEmpty()) {
                notes = null;
            }
        }
    }

    /**
     * The components this play named.
     *
     * <p>The stored list is already immutable, so this override changes no
     * behaviour. It exists because a record's generated accessor hands back the
     * field itself, and a reader of that generated code has to go and check the
     * constructor to find out whether the list is safe to hand out. Copying here
     * states the guarantee at the point a caller reads it, and costs nothing:
     * {@link List#copyOf} returns an already-immutable list unchanged.
     *
     * @return the component names, possibly empty, never null and never modifiable
     */
    @Override
    public List<String> components() {
        return List.copyOf(components);
    }

    /**
     * What the player said about the threat, if they said anything.
     *
     * <p>Offered alongside the record accessor so that callers are not tempted to
     * treat a blank note and an absent note as different things. Blank notes are
     * normalised to absent at construction.
     *
     * @return the note, or empty if none was given
     */
    public Optional<String> notesIfGiven() {
        return Optional.ofNullable(notes);
    }

    /**
     * Whether this play can take the trick, given the suit that was led.
     *
     * <p>Only a card of the led suit or a trump can take a trick. A card played
     * off-suit by a player who could not follow is a legal play that cannot win.
     *
     * @param ledSuit the suit that was led
     * @return true if this play is in contention
     */
    public boolean canTakeTrick(final StrideCategory ledSuit) {
        return card.isTrump() || card.suit() == ledSuit;
    }
}
