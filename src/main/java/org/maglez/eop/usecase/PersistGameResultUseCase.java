package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.entity.GameResult;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.ScoreSheet;
import org.maglez.eop.entity.SessionNotFoundException;

/**
 * Persists the final game result when the last trick resolves.
 *
 * <p>Called by {@link ResolveTrickUseCase} after the session transitions to
 * {@link org.maglez.eop.entity.SessionStatus#COMPLETED}. It derives the final score sheet from
 * the tricks, builds a {@link GameResult} and writes it to the repository.
 *
 * <p>The write is best-effort: if it fails, the session is still completed and the trick is still
 * resolved. The leaderboard endpoint will return 404 until the result is recorded, which is
 * preferable to rolling back a completed game because the result table was unavailable.
 *
 * <p>No authorisation check here: this use case is called internally by the trick resolution
 * path, not directly by a client. The authorisation already happened in
 * {@link ResolveTrickUseCase}.
 *
 * <p>Pure use case: no Spring, no Jakarta imports.
 */
public class PersistGameResultUseCase {

    private final SessionRepository sessionRepository;

    private final TrickRepository trickRepository;

    private final GameResultRepository gameResultRepository;

    private final IdentifierGenerator identifierGenerator;

    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository      reads the completed session and its players
     * @param trickRepository        reads all tricks to derive the final score
     * @param gameResultRepository   writes the result
     * @param identifierGenerator    generates the result identifier
     * @param clock                  supplies the finalised-at timestamp
     */
    public PersistGameResultUseCase(
            final SessionRepository sessionRepository,
            final TrickRepository trickRepository,
            final GameResultRepository gameResultRepository,
            final IdentifierGenerator identifierGenerator,
            final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.trickRepository = Objects.requireNonNull(trickRepository, "trickRepository is required");
        this.gameResultRepository = Objects.requireNonNull(gameResultRepository, "gameResultRepository is required");
        this.identifierGenerator = Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Protected no-arg constructor for test subclasses that override {@link #execute(UUID)}.
     *
     * <p>All fields are left null; the real {@link #execute(UUID)} must not be called on
     * instances created this way.
     */
    protected PersistGameResultUseCase() {
        this.sessionRepository = null;
        this.trickRepository = null;
        this.gameResultRepository = null;
        this.identifierGenerator = null;
        this.clock = null;
    }

    /**
     * Derives and persists the final game result for a completed session.
     *
     * <p>Both {@code startedAt} and {@code finalisedAt} are set to the current clock instant.
     * The session row does not carry a {@code startedAt} timestamp (only {@code updatedAt},
     * which is overwritten on every transition), so the two timestamps are equal here. They
     * are informational only and do not affect the score.
     *
     * @param sessionId  the session that just completed
     * @throws SessionNotFoundException if the session no longer exists
     */
    public void execute(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        final GameSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        final var tricks = trickRepository.findTricks(sessionId);
        final var scoreSheet = ScoreSheet.of(session.players(), tricks);
        final var finalisedAt = clock.instant();
        final var gameResultId = identifierGenerator.nextIdentifier();

        final var result = GameResult.of(gameResultId, session, scoreSheet, finalisedAt, finalisedAt);
        gameResultRepository.save(result);
    }
}
