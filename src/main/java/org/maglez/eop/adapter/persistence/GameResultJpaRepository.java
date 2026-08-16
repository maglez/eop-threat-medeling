package org.maglez.eop.adapter.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GameResultJpaEntity}.
 *
 * <p>Queries by {@code game_session_id} rather than by the result's own primary key,
 * because the application always looks up a result by the session it belongs to.
 */
interface GameResultJpaRepository extends JpaRepository<GameResultJpaEntity, UUID> {

    /**
     * Finds the result for a given session, if one has been persisted.
     *
     * @param gameSessionId the session identifier
     * @return the result row, or empty if no result has been saved yet
     */
    Optional<GameResultJpaEntity> findByGameSessionId(UUID gameSessionId);
}
