package org.maglez.eop.adapter.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.entity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the session table.
 *
 * <p>Package private, and not the application's port.
 * {@link SessionRepositoryAdapter} wraps it, so nothing above this package can
 * reach a Spring Data type.
 *
 * <p>The two update methods are deliberately conditional. Each carries the status
 * the caller believed the session was in, and reports how many rows it changed, so
 * a caller that read {@code LOBBY} and acted on it cannot have its write applied to
 * a session that has since started. Zero rows changed is not a failure of the
 * statement — it is the answer that the world moved underneath the request, and the
 * adapter turns it into a domain exception.
 */
interface GameSessionJpaRepository extends JpaRepository<GameSessionJpaEntity, UUID> {

    /**
     * Looks a session up by its shareable join code.
     *
     * @param joinCode the canonical upper-case code, exactly as stored
     * @return the row, or empty when no session carries that code
     */
    Optional<GameSessionJpaEntity> findByJoinCode(String joinCode);

    /**
     * Advances the modification timestamp, but only while the session is in the
     * expected status.
     *
     * <p>Used before seating a player. Besides validating the status, the update
     * takes a row lock that is held to the end of the transaction, which is what
     * serialises two people joining the same table in the same instant.
     *
     * @param sessionId the session to touch
     * @param required  the status the caller observed
     * @param now       the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status moved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.updatedAt = :now, s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required")
    int touchWhileInStatus(
            @Param("sessionId") UUID sessionId,
            @Param("required") SessionStatus required,
            @Param("now") OffsetDateTime now);

    /**
     * Moves the session from one status to another, but only from the expected one.
     *
     * @param sessionId the session to advance
     * @param required  the status the caller observed
     * @param target    the status to move to
     * @param now       the new modification timestamp
     * @return the number of rows changed: one on success, zero if the status moved
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameSessionJpaEntity s SET s.status = :target, s.updatedAt = :now, s.version = s.version + 1 "
            + "WHERE s.id = :sessionId AND s.status = :required")
    int advanceStatus(
            @Param("sessionId") UUID sessionId,
            @Param("required") SessionStatus required,
            @Param("target") SessionStatus target,
            @Param("now") OffsetDateTime now);
}
