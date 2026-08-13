package org.maglez.eop.adapter.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the plays made into a trick.
 *
 * <p>Package private, and not the application's port. The read deliberately imposes no
 * ordering. Play order within a trick is rotation order starting from the trick's leading
 * seat, and that is a fact the trick row carries, not something the database can express
 * in an {@code ORDER BY} over this table alone.
 *
 * <p>Ordering by {@code played_at} would look correct and be wrong. Two plays a
 * millisecond apart can share a timestamp at the column's precision, and the tie would be
 * broken arbitrarily — which matters because the first play in the list is the one the
 * domain reads the led suit from. Rotation order is exact, deterministic and derived from
 * the same rule the domain used to accept the plays, so the adapter sorts on it and this
 * interface stays silent about order rather than offering a second, plausible answer.
 */
interface TrickPlayJpaRepository extends JpaRepository<TrickPlayJpaEntity, UUID> {

    /**
     * Reads every play made into a trick.
     *
     * @param trickId the trick whose plays to read
     * @return the plays, in no guaranteed order, empty if nobody has played yet
     */
    List<TrickPlayJpaEntity> findByTrickId(UUID trickId);
}
