package org.maglez.eop.usecase;

import java.util.Objects;
import org.maglez.eop.entity.GameResult;
import org.maglez.eop.entity.ScoreSheet;

/**
 * Bundles the persisted {@link GameResult} with the derived {@link ScoreSheet} for the same session.
 *
 * <p>The leaderboard endpoint needs both: the {@code GameResult} for the standings and metadata,
 * and the {@code ScoreSheet} for the per-suit STRIDE breakdown. Returning them together from
 * {@link GetLeaderboardUseCase} avoids a second use-case call in the controller and keeps the
 * controller free of {@link GetScoreUseCase} as a dependency.
 *
 * <p>Pure value type: no Spring, no Jakarta imports.
 */
public record LeaderboardResult(GameResult gameResult, ScoreSheet scoreSheet) {

    /**
     * Creates a leaderboard result.
     *
     * @param gameResult the persisted game result
     * @param scoreSheet the derived score sheet for the same session
     */
    public LeaderboardResult {
        Objects.requireNonNull(gameResult, "gameResult is required");
        Objects.requireNonNull(scoreSheet, "scoreSheet is required");
    }
}
