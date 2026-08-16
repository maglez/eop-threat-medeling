package org.maglez.eop.adapter.web;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maglez.eop.entity.ScoreSheet;
import org.maglez.eop.entity.ScoredPlay;
import org.maglez.eop.entity.Standing;
import org.maglez.eop.entity.StrideCategory;

/**
 * The final leaderboard for a completed game.
 *
 * <p>Carries one row per seated player, ordered by competition-ranking position (best first,
 * seat order as tiebreak), plus the session status string for the client to confirm the game
 * is over.
 *
 * @param rows          one row per player, best first
 * @param sessionStatus the session status at the time the leaderboard was read
 */
public record LeaderboardDto(List<LeaderboardRowDto> rows, String sessionStatus) {

    /** Defensive copy. */
    public LeaderboardDto {
        rows = List.copyOf(rows);
    }

    /**
     * Builds the leaderboard from a persisted {@link GameResult} and the live {@link ScoreSheet}.
     *
     * <p>Scores, positions and the {@code tied} flag are always derived from the live
     * {@link ScoreSheet} (re-read from tricks at request time), never from the stored
     * {@code GameResult}. This satisfies ADR-030: a persisted standing must never be
     * read back to answer the score. The {@code GameResult} is used only for metadata
     * (facilitator name, timestamps) that the DTO does not currently expose.
     *
     * <p>The per-player STRIDE breakdown is computed by scanning the scored-play rows:
     * for each trick won by a player, every card played in that trick contributes one
     * count to that player's suit tally.
     *
     * @param scoreSheet  the score sheet derived from the session's tricks
     * @param sessionStatus the session status string
     * @return the leaderboard response body
     */
    public static LeaderboardDto from(final ScoreSheet scoreSheet, final String sessionStatus) {

        final Map<UUID, Map<StrideCategory, Integer>> capturedBySuit = computeCapturedBySuit(scoreSheet);

        final List<LeaderboardRowDto> rows = scoreSheet.standings().stream()
                .map(standing -> toRow(standing, capturedBySuit))
                .toList();

        return new LeaderboardDto(rows, sessionStatus);
    }

    private static LeaderboardRowDto toRow(
            final Standing standing, final Map<UUID, Map<StrideCategory, Integer>> capturedBySuit) {

        final Map<StrideCategory, Integer> byCategory =
                capturedBySuit.getOrDefault(standing.playerId(), Map.of());

        // Build a string-keyed map with all six STRIDE categories present (zero if none captured)
        final Map<String, Integer> capturedMap = new HashMap<>();
        for (final StrideCategory category : StrideCategory.values()) {
            capturedMap.put(category.name(), byCategory.getOrDefault(category, 0));
        }

        return new LeaderboardRowDto(
                standing.playerId(),
                standing.seatOrder(),
                standing.displayName().value(),
                standing.points(),
                standing.position(),
                standing.tied(),
                Map.copyOf(capturedMap));
    }

    /**
     * Computes how many cards of each STRIDE category each player captured.
     *
     * <p>A player captures all cards in a trick they win. The scored-play rows are in trick
     * order; within a trick they appear in play order. A trick boundary is detected by the
     * presence of a {@code trickPoint} row (the winner). All rows since the last boundary
     * belong to the same trick, and their cards are attributed to the winner.
     *
     * <p>An unresolved trick (no winner yet) contributes no captures.
     */
    private static Map<UUID, Map<StrideCategory, Integer>> computeCapturedBySuit(
            final ScoreSheet scoreSheet) {

        final Map<UUID, Map<StrideCategory, Integer>> result = new HashMap<>();
        final List<ScoredPlay> rows = scoreSheet.rows();

        // Collect plays in the current trick window; flush when we find the winner
        final List<ScoredPlay> currentTrick = new ArrayList<>();
        for (final ScoredPlay play : rows) {
            currentTrick.add(play);
            if (play.trickPoint()) {
                // This play won the trick — attribute all cards in the window to this player
                final UUID winnerId = play.playerId();
                final Map<StrideCategory, Integer> tally =
                        result.computeIfAbsent(winnerId, id -> new EnumMap<>(StrideCategory.class));
                for (final ScoredPlay captured : currentTrick) {
                    tally.merge(captured.card().suit(), 1, Integer::sum);
                }
                currentTrick.clear();
            }
        }
        // Any remaining plays are from an unresolved trick — no captures yet

        return result;
    }
}
