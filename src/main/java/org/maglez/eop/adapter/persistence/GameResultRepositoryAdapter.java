package org.maglez.eop.adapter.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.DisplayName;
import org.maglez.eop.entity.GameResult;
import org.maglez.eop.usecase.IdentifierGenerator;
import org.maglez.eop.entity.Standing;
import org.maglez.eop.usecase.GameResultRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and retrieves {@link GameResult} aggregates.
 *
 * <p>A result is write-once: once saved it is never updated. The {@link #save} method
 * inserts the header row and all player rows in a single transaction.
 *
 * <p>On read, {@code position} and {@code tied} are recomputed from the stored scores
 * rather than being stored in the database. This keeps the schema simple and avoids
 * stale ranking data if the computation logic ever changes.
 */
@Repository
public class GameResultRepositoryAdapter implements GameResultRepository {

    private final GameResultJpaRepository resultRows;

    private final GameResultPlayerJpaRepository playerRows;

    private final IdentifierGenerator identifierGenerator;

    GameResultRepositoryAdapter(
            final GameResultJpaRepository resultRows,
            final GameResultPlayerJpaRepository playerRows,
            final IdentifierGenerator identifierGenerator) {
        this.resultRows = Objects.requireNonNull(resultRows, "resultRows is required");
        this.playerRows = Objects.requireNonNull(playerRows, "playerRows is required");
        this.identifierGenerator =
                Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
    }

    @Override
    @Transactional
    public void save(final GameResult result) {
        Objects.requireNonNull(result, "result is required");
        resultRows.saveAndFlush(GameResultJpaEntity.fromDomain(result));
        for (final Standing standing : result.standings()) {
            playerRows.saveAndFlush(
                    GameResultPlayerJpaEntity.fromDomain(
                            identifierGenerator.nextIdentifier(), result.gameResultId(), standing));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GameResult> findBySessionId(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        return resultRows.findByGameSessionId(sessionId).map(this::assemble);
    }

    /**
     * Rebuilds a {@link GameResult} from its header row and player rows.
     *
     * <p>Position and tied are recomputed from the stored scores using competition
     * ranking: equal scores share a position, and the next distinct score is placed
     * at (number of players with strictly higher scores + 1).
     *
     * @param header the result header row
     * @return the reconstituted domain object
     */
    private GameResult assemble(final GameResultJpaEntity header) {
        final var rows = playerRows.findByGameResultIdOrderBySeatOrderAsc(header.getId());
        final var standings = rankStandings(rows);
        return header.toDomain(standings);
    }

    /**
     * Converts player rows into ranked {@link Standing} objects.
     *
     * <p>Uses competition ranking (1224 ranking): equal scores share a position, and
     * the next distinct score is placed at (number of players with strictly higher
     * scores + 1). For example, scores 7, 5, 5, 2 yield positions 1, 2, 2, 4.
     *
     * @param rows the player rows in seat order
     * @return standings with correct position and tied values, in seat order
     */
    private static List<Standing> rankStandings(final List<GameResultPlayerJpaEntity> rows) {
        final var result = new ArrayList<Standing>(rows.size());
        for (final var row : rows) {
            final int score = row.getScore();
            final int position = (int) rows.stream()
                    .filter(r -> r.getScore() > score)
                    .count() + 1;
            final boolean tied = rows.stream()
                    .filter(r -> !r.getId().equals(row.getId()))
                    .anyMatch(r -> r.getScore() == score);
            result.add(new Standing(
                    row.getPlayerId(),
                    row.getSeatOrder(),
                    new DisplayName(row.getDisplayName()),
                    score,
                    position,
                    tied));
        }
        return result;
    }
}
