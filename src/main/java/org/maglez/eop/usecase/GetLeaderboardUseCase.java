package org.maglez.eop.usecase;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.GameNotCompletedException;
import org.maglez.eop.entity.PlayerNotRecognisedException;
import org.maglez.eop.entity.ScoreSheet;
import org.maglez.eop.entity.SessionNotFoundException;
import org.maglez.eop.entity.SessionStatus;

/**
 * Reads the final leaderboard for a completed session.
 *
 * <p>The leaderboard is only available once the session is in
 * {@link SessionStatus#COMPLETED} state. Requesting it while the session is still
 * in progress is a conflict — the resource exists but is not yet in the right state.
 *
 * <p>Any seated player may read the leaderboard. The result is public knowledge once
 * the game ends: every card has been played face up and the scores are derived from
 * those plays. Membership is still required so that a stranger who guesses a session
 * identifier cannot read the result (ADR-024).
 *
 * <p>Returns a {@link LeaderboardResult} that bundles the persisted {@link org.maglez.eop.entity.GameResult}
 * with the derived {@link ScoreSheet}, so the controller can build the full leaderboard DTO
 * (including per-suit STRIDE breakdown) from a single use-case call.
 *
 * <p>Pure use case: no Spring, no Jakarta imports.
 */
public class GetLeaderboardUseCase {

    private final ResolvePlayerUseCase resolvePlayerUseCase;

    private final GameResultRepository gameResultRepository;

    private final TrickRepository trickRepository;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerUseCase  resolves the acting player from the identity token
     * @param gameResultRepository  reads the persisted game result
     * @param trickRepository       reads the tricks for the STRIDE breakdown
     */
    public GetLeaderboardUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final GameResultRepository gameResultRepository,
            final TrickRepository trickRepository) {
        this.resolvePlayerUseCase = Objects.requireNonNull(resolvePlayerUseCase, "resolvePlayerUseCase is required");
        this.gameResultRepository = Objects.requireNonNull(gameResultRepository, "gameResultRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
    }

    /**
     * Reads the leaderboard for a completed session.
     *
     * @param sessionId   the session to read the leaderboard for
     * @param playerToken the caller's identity token, as presented
     * @return the leaderboard result containing the game result and score sheet
     * @throws NullPointerException              if sessionId is null
     * @throws SessionNotFoundException          if no session has that identifier, or the session
     *                                           is completed but no result has been persisted yet
     * @throws PlayerNotRecognisedException      if the token names nobody at this table
     * @throws GameNotCompletedException         if the session is not yet completed
     */
    public LeaderboardResult execute(final UUID sessionId, final String playerToken) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        final var resolved = resolvePlayerUseCase.execute(sessionId, playerToken);
        if (resolved.session().status() != SessionStatus.COMPLETED) {
            throw new GameNotCompletedException(sessionId);
        }
        final var gameResult = gameResultRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        final var scoreSheet = ScoreSheet.of(resolved.session().players(),
                trickRepository.findTricks(sessionId));
        return new LeaderboardResult(gameResult, scoreSheet);
    }
}
