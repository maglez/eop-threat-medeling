package org.maglez.eop.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The score of one game, computed from the cards that have been played.
 *
 * <p>This is the whole of the shipped scoring rule, and it is deliberately small: <strong>one point for a threat on your card, plus one
 * point for taking the trick</strong> (PRD §3.4). It must not be embellished. A richer system was tried and abandoned — four points for a
 * threat on your own card, three, two and one for threats on other people's, two for a face card, three for an ace — not because it was a
 * worse game but because playtesters could not compute it at the table and looked to the facilitator at the end of every round to find out
 * who had scored. A server does not have that limitation, so a richer rule is cheap to add later and is worth recording as an option; it is
 * explicitly not part of this story.</p>
 *
 * <p>The score is <em>derived</em>, not accumulated. Every total here is a pure function of the plays and of which play took each trick, so
 * it cannot drift away from the play that produced it, and there is no running tally anywhere that could be wrong. That also means the
 * server is the only thing that ever computes a score: a client is shown totals and never asserts its own.</p>
 *
 * <p>The list of tricks may include one that is still in progress. Threat points are earned when a card is played with a threat connected
 * to it, so they count immediately; the trick point is earned by taking the trick, so it only counts once the trick has a winner. The
 * consequence is that a running score only ever grows, and that a player watching the sheet sees their threat point the moment they play
 * rather than when the trick is swept.</p>
 *
 * <p>What this class deliberately does <em>not</em> do is decide that the game has ended, or move the session to a terminal state. It
 * answers what the score is; whether the score is final is a fact about the session, which the session owns.</p>
 */
public final class ScoreSheet {

    private final List<ScoredPlay> rows;

    private final List<Standing> standings;

    private ScoreSheet(final List<ScoredPlay> rows, final List<Standing> standings) {
        this.rows = List.copyOf(rows);
        this.standings = List.copyOf(standings);
    }

    /**
     * Scores a game from its seated players and its tricks.
     *
     * <p>Tricks are read in sequence order regardless of the order they arrive in, so the rows come out in the order the cards were played.
     * At most one of them may be unresolved — the trick currently on the table — and its plays contribute their threat points but no trick
     * point, because nobody has taken it yet.</p>
     *
     * <p>A play by somebody who is not seated, or a play whose seat disagrees with its player's seat, is a contradiction rather than a
     * client error, and is refused as one. Neither is reachable through the game's own writes; both are cheap to rule out here and would
     * otherwise produce a plausible but wrong sheet.</p>
     *
     * @param players the seated players, at least one
     * @param tricks  the tricks played so far, possibly empty, possibly including one still in progress
     * @return the score of the game as it stands
     * @throws IllegalArgumentException if there are no players, if two tricks collide on identity or sequence, or if a play cannot be
     *                                  attributed to a seated player
     */
    public static ScoreSheet of(final List<Player> players, final List<Trick> tricks) {
        Objects.requireNonNull(players, "players is required");
        Objects.requireNonNull(tricks, "tricks is required");
        if (players.isEmpty()) {
            throw new IllegalArgumentException("A score sheet needs at least one seated player");
        }
        final Map<UUID, Player> seated = index(players);
        final List<ScoredPlay> scored = new ArrayList<>();
        for (final Trick trick : inSequenceOrder(tricks)) {
            final Optional<TrickPlay> winner = trick.winner();
            for (final TrickPlay play : trick.plays()) {
                final Player player = seated.get(play.playerId());
                if (player == null) {
                    throw new IllegalArgumentException("Play " + play.trickPlayId() + " was made by a player who is not seated");
                }
                if (player.seatOrder() != play.seatOrder()) {
                    throw new IllegalArgumentException("Play " + play.trickPlayId() + " names seat " + play.seatOrder()
                            + " but its player is seated at " + player.seatOrder());
                }
                final boolean tookTrick = winner.isPresent() && winner.get().trickPlayId().equals(play.trickPlayId());
                scored.add(ScoredPlay.of(player, play, tookTrick));
            }
        }
        return new ScoreSheet(scored, rank(players, scored));
    }

    private static Map<UUID, Player> index(final List<Player> players) {
        final Map<UUID, Player> seated = new LinkedHashMap<>();
        for (final Player player : players) {
            Objects.requireNonNull(player, "A player is required");
            if (seated.put(player.playerId(), player) != null) {
                throw new IllegalArgumentException("Player " + player.playerId() + " is seated twice");
            }
        }
        return seated;
    }

    private static List<Trick> inSequenceOrder(final List<Trick> tricks) {
        final Set<UUID> identifiers = new HashSet<>();
        final Set<Integer> sequences = new HashSet<>();
        for (final Trick trick : tricks) {
            Objects.requireNonNull(trick, "A trick is required");
            if (!identifiers.add(trick.trickId())) {
                throw new IllegalArgumentException("Trick " + trick.trickId() + " appears twice");
            }
            if (!sequences.add(trick.sequence())) {
                throw new IllegalArgumentException("Two tricks claim sequence " + trick.sequence());
            }
        }
        final List<Trick> ordered = new ArrayList<>(tricks);
        ordered.sort(Comparator.comparingInt(Trick::sequence));
        return ordered;
    }

    private static List<Standing> rank(final List<Player> players, final List<ScoredPlay> scored) {
        final Map<UUID, Integer> totals = new HashMap<>();
        for (final Player player : players) {
            totals.put(player.playerId(), 0);
        }
        for (final ScoredPlay row : scored) {
            totals.merge(row.playerId(), row.points(), Integer::sum);
        }
        final List<Player> ranked = new ArrayList<>(players);
        ranked.sort(Comparator.comparingInt((final Player player) -> totals.get(player.playerId())).reversed()
                .thenComparingInt(Player::seatOrder));
        final List<Standing> standings = new ArrayList<>();
        int position = 0;
        int place = 0;
        Integer previous = null;
        for (final Player player : ranked) {
            place++;
            final int points = totals.get(player.playerId());
            if (previous == null || points != previous) {
                position = place;
                previous = points;
            }
            final boolean tied = Collections.frequency(totals.values(), points) > 1;
            standings.add(new Standing(player.playerId(), player.seatOrder(), player.displayName(), points, position, tied));
        }
        return standings;
    }

    /**
     * The Score Card rows, in the order the cards were played.
     *
     * @return one row per play, oldest first
     */
    public List<ScoredPlay> rows() {
        return rows;
    }

    /**
     * Every seated player's total, best first, with a seat order tiebreak so that the list itself is stable.
     *
     * <p>The order is presentation only. Two players on the same total hold the same {@link Standing#position()}, and the seat used to
     * order them says nothing about which of them did better.</p>
     *
     * @return one standing per seated player, including players who have scored nothing
     */
    public List<Standing> standings() {
        return standings;
    }

    /**
     * Whoever is in front, which may be several players at once.
     *
     * @return every standing in first position — more than one when the lead is shared
     */
    public List<Standing> leaders() {
        return standings.stream().filter(standing -> standing.position() == 1).toList();
    }

    /**
     * Whether the lead is shared.
     *
     * @return true when more than one player holds first position
     */
    public boolean leadIsShared() {
        return leaders().size() > 1;
    }

    /**
     * One player's total.
     *
     * @param playerId identifier of a seated player
     * @return that player's points, zero if they have not scored
     * @throws IllegalArgumentException if the player is not seated in this game
     */
    public int pointsOf(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId is required");
        return standings.stream()
                .filter(standing -> standing.playerId().equals(playerId))
                .mapToInt(Standing::points)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No player " + playerId + " is seated in this game"));
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScoreSheet that)) {
            return false;
        }
        return rows.equals(that.rows) && standings.equals(that.standings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rows, standings);
    }

    /**
     * Describes the sheet without reproducing it.
     *
     * <p>Every row names a card that is already on the table, so nothing here is confidential, but a sheet late in a game is long and a log
     * line is not where anybody reads a score. The shape is what is useful in a trace; the sheet itself is what the endpoint returns.</p>
     *
     * @return the number of rows, the number of players and the leading total
     */
    @Override
    public String toString() {
        final int leading = leaders().stream().mapToInt(Standing::points).findFirst().orElse(0);
        return "ScoreSheet[rows=" + rows.size() + ", players=" + standings.size() + ", leading=" + leading + "]";
    }
}
