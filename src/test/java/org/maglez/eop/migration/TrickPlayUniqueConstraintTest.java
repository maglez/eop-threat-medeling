package org.maglez.eop.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the unique constraints on {@code trick_play} that guard the
 * Slice A security defects are enforced by the database engine, not merely declared.
 *
 * <p>The constraints under test, read directly from
 * {@code 004-trick-play-schema.xml} changeset 005:
 * <ul>
 *   <li>{@code uq_trick_play_trick_seat} — one seat per trick (ADR-023 concurrent-play gate)</li>
 *   <li>{@code uq_trick_play_trick_card} — one card per trick (stored form of card-forgery defect)</li>
 *   <li>{@code uq_trick_play_trick_player} — one player per trick.  Retained as defence in depth:
 *       it is what would still forbid one player occupying two seats in a trick if the seat binding
 *       ({@code fk_trick_play_player_seat}, changeset 009) were ever relaxed.  It is not pinned by
 *       an independent enforcement test because, with the seat binding in place, every construction
 *       that would violate it also violates the FK.  On H2 2.4.240 the unique constraint is the one
 *       reported — measured, not assumed.  Which one PostgreSQL 17 reports has not been measured
 *       here, and it is not safe to assume it agrees: the engines are free to check in either order,
 *       which is the whole reason asserting either constraint name would be a portability trap.  See
 *       {@link #secondPlayBySamePlayerAtAnotherSeatIsRejected} for the deterministic test that
 *       replaced the former portability-trap form.</li>
 * </ul>
 *
 * <p>Test 1 ({@link #concurrentDoublePlayForSameSeatIsRejected}) is the ADR-023 obligation:
 * two threads on two separate JDBC connections race to insert a {@code trick_play} row for
 * the same {@code (trick_id, seat_order)}.  A {@link CountDownLatch} ensures both threads
 * are provably at the insert at the same moment.  Exactly one must succeed and exactly one
 * must fail with a {@link SQLIntegrityConstraintViolationException} (SQL state {@code 23505}).
 * The count for that {@code (trick_id, seat_order)} must be exactly 1 afterwards.
 *
 * <p>Tests 2 and 4 are sequential: they assert that a second play in the same trick is
 * rejected when it reuses the same {@code card_id} (test 2), or the same {@code seat_order}
 * claimed by a player from another session (test 4).
 *
 * <p>Test 3 ({@link #secondPlayBySamePlayerAtAnotherSeatIsRejected}) asserts that a play
 * at a seat the player does not hold is rejected by {@code fk_trick_play_player_seat} (SQL
 * state {@code 23506}).  It uses a fresh trick (no prior play by the same player) so that
 * {@code uq_trick_play_trick_player} cannot fire, making the FK the only violable constraint
 * and the constraint name deterministic on both engines.
 *
 * <p>No Spring context.  Owns its own uniquely-named in-memory H2 database.
 * No JPA, no Spring Data, no adapters — JDBC only, as required by Slice B.
 */
@DisplayName("trick_play unique constraints enforce the Slice A security invariants")
class TrickPlayUniqueConstraintTest {

    /**
     * Unique in-memory database name — does not collide with the suite's eop-test or
     * with the other migration test databases.
     */
    private static final String JDBC_URL =
            "jdbc:h2:mem:trick-play-unique-constraint;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /**
     * SQL state H2 emits on a unique-index or primary-key violation.
     * Verified empirically: H2 2.4.240 throws
     * {@code org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException} with
     * {@code getSQLState() == "23505"} and {@code getErrorCode() == 23505}.
     * That class IS-A {@link SQLIntegrityConstraintViolationException}, so both
     * the class assertion and the SQL-state assertion are reliable on H2.
     */
    private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    private Connection connection;
    private Liquibase liquibase;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        final Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase(
                "db/changelog/db.changelog-master.xml",
                new ClassLoaderResourceAccessor(),
                database);
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase leaves the connection with auto-commit disabled after running migrations.
        // Re-enable it so that rows inserted in the test body are immediately visible to
        // worker threads that open their own connections to the same in-memory database.
        connection.setAutoCommit(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (liquibase != null) {
            liquibase.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        try (Connection dropConn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var stmt = dropConn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS DELETE FILES");
        } catch (SQLException ignored) {
            // Best-effort cleanup.
        }
    }

    // =========================================================================
    // Test 1 — ADR-023 concurrent double-play obligation
    // =========================================================================

    /**
     * One player races itself: two threads on two separate JDBC connections both try to
     * insert a {@code trick_play} row for the same player at the same seat, with two
     * different cards.
     *
     * <p>Concurrency mechanism: a {@link CountDownLatch} of size 2 is used as a
     * starting gate.  Each thread counts down and then awaits, so neither thread
     * proceeds to the INSERT until both are ready.  This is real concurrency, not
     * sequential calls dressed up as concurrency.
     *
     * <p>Determinism: the test does not assert which thread won — that is genuinely
     * nondeterministic.  It asserts only the outcome that the constraint guarantees:
     * exactly one success, exactly one failure, and a final count of 1.
     *
     * <p>Why the same player, not two different players: the composite foreign key
     * {@code fk_trick_play_player_seat} (changeset 009) binds {@code (player_id,
     * seat_order)} to {@code player(id, seat_order)}.  A two-player race where both
     * claim the same play-seat would have one player forging a seat it does not hold,
     * violating {@code fk_trick_play_player_seat} as well as
     * {@code uq_trick_play_trick_seat}.  H2's choice of which violated constraint to
     * report is nondeterministic when one row breaks both at once — empirically 7 of 10
     * repetitions produced {@code 23506} (FK violation) rather than {@code 23505}
     * (unique violation), making the two-player form inherently flaky.  The same-player
     * race is the ordinary double-tap a real client produces (one player submitting
     * twice), and it is precisely the case ADR-023 requires the constraint to reject.
     *
     * <p>The losing row violates both {@code uq_trick_play_trick_seat} and
     * {@code uq_trick_play_trick_player} simultaneously (same trick, same seat, same
     * player).  The engine may report either constraint name.  This test asserts only
     * SQL state {@code 23505}, not the constraint name, to avoid reintroducing the
     * flake.
     *
     * <p>This test is annotated {@link RepeatedTest @RepeatedTest(10)} to demonstrate
     * that it is stable across repeated runs.  All 10 repetitions must pass.
     */
    @RepeatedTest(10)
    @DisplayName("same player racing itself for the same (trick_id, seat_order) — exactly one succeeds, one fails with 23505")
    void concurrentDoublePlayForSameSeatIsRejected() throws Exception {
        // Arrange — one player at seat N; two different cards for the two threads
        final UUID cardId1 = MigrationTestFixtures.anyExistingCard(connection);
        final UUID cardId2 = MigrationTestFixtures.secondExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrick(connection);
        final int sharedSeat = 1;
        // One real player at seat 1 in the trick's session. Both threads will insert
        // a play for this same player at this same seat — the ordinary double-tap.
        final UUID playerId = MigrationTestFixtures.insertPlayerForTrick(connection, trickId, sharedSeat);

        // Starting gate: both threads count down and then await before inserting.
        final CountDownLatch startGate = new CountDownLatch(2);

        // Outcome accumulators — written by the worker threads, read by the main thread.
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicReference<String> failureSqlState = new AtomicReference<>(null);
        final AtomicReference<String> unexpectedError1 = new AtomicReference<>(null);
        final AtomicReference<String> unexpectedError2 = new AtomicReference<>(null);

        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<?> thread1 = pool.submit(() -> {
                try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
                    startGate.countDown();
                    startGate.await();   // wait until thread 2 is also ready
                    MigrationTestFixtures.insertTrickPlay(conn, trickId, cardId1, sharedSeat, playerId);
                    successCount.incrementAndGet();
                } catch (SQLIntegrityConstraintViolationException e) {
                    failureCount.incrementAndGet();
                    failureSqlState.set(e.getSQLState());
                } catch (Exception e) {
                    unexpectedError1.set(e.getClass().getName() + ": " + e.getMessage());
                    // Rethrow as unchecked so the Future carries it.
                    throw new RuntimeException("Thread 1 failed unexpectedly", e);
                }
            });

            final Future<?> thread2 = pool.submit(() -> {
                try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
                    startGate.countDown();
                    startGate.await();   // wait until thread 1 is also ready
                    MigrationTestFixtures.insertTrickPlay(conn, trickId, cardId2, sharedSeat, playerId);
                    successCount.incrementAndGet();
                } catch (SQLIntegrityConstraintViolationException e) {
                    failureCount.incrementAndGet();
                    failureSqlState.set(e.getSQLState());
                } catch (Exception e) {
                    unexpectedError2.set(e.getClass().getName() + ": " + e.getMessage());
                    throw new RuntimeException("Thread 2 failed unexpectedly", e);
                }
            });

            // Act — wait for both threads to finish (generous timeout; H2 in-memory is fast)
            pool.shutdown();
            final boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(finished)
                    .as("both threads must complete within 10 seconds")
                    .isTrue();

            // Propagate any unexpected exception from either thread
            thread1.get();
            thread2.get();

        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }

        // Assert — exactly one success and one failure
        assertThat(unexpectedError1.get())
                .as("thread 1 must not throw an unexpected (non-constraint) exception")
                .isNull();
        assertThat(unexpectedError2.get())
                .as("thread 2 must not throw an unexpected (non-constraint) exception")
                .isNull();
        assertThat(successCount.get())
                .as("exactly one insert must succeed for (trick_id=%s, seat_order=%d)", trickId, sharedSeat)
                .isEqualTo(1);
        assertThat(failureCount.get())
                .as("exactly one insert must fail for (trick_id=%s, seat_order=%d)", trickId, sharedSeat)
                .isEqualTo(1);

        // Assert — the failure is a unique-constraint violation (SQL state 23505).
        // The losing row violates both uq_trick_play_trick_seat and uq_trick_play_trick_player;
        // the engine may report either. We assert only the SQL state, not the constraint name.
        assertThat(failureSqlState.get())
                .as("the failing insert must produce SQL state %s (unique violation), not some other error",
                        SQL_STATE_UNIQUE_VIOLATION)
                .isEqualTo(SQL_STATE_UNIQUE_VIOLATION);

        // Assert — exactly one row exists for that (trick_id, seat_order)
        final int count = MigrationTestFixtures.countTrickPlaysBySeat(connection, trickId, sharedSeat);
        assertThat(count)
                .as("SELECT COUNT(*) for (trick_id=%s, seat_order=%d) must be 1 after the race",
                        trickId, sharedSeat)
                .isEqualTo(1);
    }

    // =========================================================================
    // Test 2 — card-forgery defect: uq_trick_play_trick_card
    // =========================================================================

    /**
     * A second play in the same trick that reuses the same {@code card_id} must be
     * rejected by {@code uq_trick_play_trick_card}.
     *
     * <p>This is the stored form of the card-forgery defect that Slice A had to fix
     * twice at the domain level.  The constraint name is read from
     * {@code 004-trick-play-schema.xml} changeset 005, line 323:
     * {@code constraintName="uq_trick_play_trick_card"}.
     */
    @Test
    @DisplayName("second play in the same trick reusing the same card_id is rejected by uq_trick_play_trick_card")
    void secondPlayWithSameCardIsRejected() throws Exception {
        // Arrange — one trick, one card, two different seats and players
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrick(connection);

        // First play: seat 0, player A, card X — must succeed
        final UUID playerA = MigrationTestFixtures.insertPlayerForTrick(connection, trickId, 0);
        MigrationTestFixtures.insertTrickPlay(connection, trickId, cardId, 0, playerA);

        // Act + Assert — second play: seat 1, player B, same card X — must fail
        final UUID playerB = MigrationTestFixtures.insertPlayerForTrick(connection, trickId, 1);
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlay(connection, trickId, cardId, 1, playerB))
                .as("a second play in the same trick with the same card_id must be rejected "
                        + "by uq_trick_play_trick_card (SQL state 23505)")
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_UNIQUE_VIOLATION);
    }

    // =========================================================================
    // Test 3 — seat-impersonation defect: fk_trick_play_player_seat
    // =========================================================================

    /**
     * A play by a player at a seat the player does not hold must be rejected by
     * {@code fk_trick_play_player_seat} with SQL state {@code 23506}.
     *
     * <p>This is the structural closure of the seat-impersonation defect at the storage
     * layer: {@code fk_trick_play_player_seat} (changeset 009) references
     * {@code player(id, seat_order)}, so a row whose {@code (player_id, seat_order)} pair
     * has no matching {@code player} row is rejected by the engine.  Because the player
     * holds seat 0, the pair {@code (playerId, 1)} has no matching row, and the insert is
     * rejected regardless of whether seat 1 is occupied by another player.
     *
     * <h3>Why {@code uq_trick_play_trick_player} is no longer pinned by this test</h3>
     * <p>Before changeset 009 added {@code fk_trick_play_player_seat}, the only way to
     * construct a "same player, different seat" row was to have a prior play by the same
     * player in the same trick, so {@code uq_trick_play_trick_player} and the FK were
     * violated simultaneously.  H2 evaluates unique constraints before foreign-key
     * constraints, so H2 reported {@code 23505} ({@code uq_trick_play_trick_player}) while
     * PostgreSQL 17 would report {@code 23506} ({@code fk_trick_play_player_seat}) — a
     * portability trap of the same class as the one fixed in
     * {@code twoHandsInSameSessionAtSameSeatAreRejected}.
     *
     * <p>The correct fix is to isolate the FK as the <em>only</em> violable constraint.
     * That requires a fresh trick (no prior play by this player), so
     * {@code uq_trick_play_trick_player} cannot fire.  The non-vacuity insert (the
     * happy-path play at the player's own seat in a separate trick) proves the schema is
     * not simply rejecting all inserts.
     *
     * <p>{@code uq_trick_play_trick_player} is retained in the schema as defence in depth:
     * it is what would still forbid one player occupying two seats in a trick if the seat
     * binding ({@code fk_trick_play_player_seat}) were ever relaxed.  It is not pinned by
     * an enforcement test here because, with the seat binding in place, every construction
     * that would violate it also violates the FK, and the FK fires first on PostgreSQL.
     * If {@code fk_trick_play_player_seat} were ever dropped, this test would need to be
     * rewritten to assert {@code 23505} and name {@code uq_trick_play_trick_player}.
     *
     * <h3>Why naming the constraint is safe here</h3>
     * <p>Walk every constraint on {@code trick_play} for the second insert:
     * <ul>
     *   <li>{@code fk_trick_play_trick} — satisfied: the trick exists.</li>
     *   <li>{@code fk_trick_play_card} — satisfied: the card is in the seeded catalogue.</li>
     *   <li>{@code fk_trick_play_player_seat} — <strong>violated</strong>: the player holds
     *       seat 0; the pair {@code (playerId, 1)} has no matching {@code player(id,
     *       seat_order)} row.  This is the only constraint the insert can violate.</li>
     *   <li>{@code uq_trick_play_trick_seat} — not violated: no prior play at seat 1 in
     *       this trick.</li>
     *   <li>{@code uq_trick_play_trick_card} — not violated: the card is different from
     *       the non-vacuity play's card.</li>
     *   <li>{@code uq_trick_play_trick_player} — not violated: the player has no prior
     *       play in this trick (the non-vacuity play is in a separate trick).</li>
     * </ul>
     * <p>Exactly one constraint can fire, so the constraint name in the exception message
     * is deterministic on both H2 and PostgreSQL 17.
     *
     * <p>Empirically verified: H2 2.4.240 produces the message
     * {@code Referential integrity constraint violation: "FK_TRICK_PLAY_PLAYER_SEAT:
     * PUBLIC.TRICK_PLAY FOREIGN KEY(PLAYER_ID, SEAT_ORDER) REFERENCES
     * PUBLIC.PLAYER(ID, SEAT_ORDER) (UUID '...', 1)"}.
     * The constraint name {@code FK_TRICK_PLAY_PLAYER_SEAT} appears verbatim in the message.
     */
    @Test
    @DisplayName("play at a seat the player does not hold is rejected by fk_trick_play_player_seat (23506)")
    void secondPlayBySamePlayerAtAnotherSeatIsRejected() throws Exception {
        // Arrange — player holds seat 0; two separate tricks so uq_trick_play_trick_player
        // cannot fire on the second insert (the player has no prior play in trick 2).
        final UUID cardId1 = MigrationTestFixtures.anyExistingCard(connection);
        final UUID cardId2 = MigrationTestFixtures.secondExistingCard(connection);

        // Session A: player holds seat 0.  Trick 1 is the non-vacuity vehicle.
        final UUID sessionA = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID trickId1 = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionA);
        // Player holds seat 0 in session A.
        final UUID playerId = MigrationTestFixtures.insertPlayerInSession(connection, sessionA, 0);

        // Non-vacuity: play at the player's own seat (seat 0) in trick 1 must succeed.
        // If the schema were rejecting everything, this would fail before the assertion below.
        MigrationTestFixtures.insertTrickPlay(connection, trickId1, cardId1, 0, playerId);

        // Session B: a separate session so that insertMinimalTrickInSession (which always
        // uses sequence=1) does not collide with trick 1 on uq_trick_session_sequence.
        // The player has no prior play in trick 2, so uq_trick_play_trick_player cannot fire.
        final UUID sessionB = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID trickId2 = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionB);

        // Act + Assert — play at seat 1 in trick 2 by the same player (who holds seat 0).
        // (playerId, 1) has no matching player(id, seat_order) row → fk_trick_play_player_seat fires.
        // Only one constraint can fire here (see Javadoc for the full constraint walk).
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlay(connection, trickId2, cardId2, 1, playerId))
                .as("a play at seat 1 by a player who holds seat 0 must be rejected "
                        + "by fk_trick_play_player_seat (SQL state 23506, constraint name in message)")
                .isInstanceOf(SQLException.class)
                .satisfies(e -> {
                    final SQLException sqle = (SQLException) e;
                    assertThat(sqle.getSQLState())
                            .as("SQL state must be 23506 (FK violation)")
                            .isEqualTo("23506");
                    // The constraint name is deterministic here because fk_trick_play_player_seat
                    // is the only constraint the insert can violate (see Javadoc).
                    // H2 2.4.240 includes the constraint name verbatim in the exception message.
                    assertThat(sqle.getMessage())
                            .as("H2 exception message must name the violated constraint")
                            .containsIgnoringCase("FK_TRICK_PLAY_PLAYER_SEAT");
                });
    }

    // =========================================================================
    // Test 4 — seat-lock-out attack: uq_trick_play_trick_seat in isolation
    // =========================================================================

    /**
     * A cross-session player who holds the same seat number as a legitimate player can
     * take that seat in the trick, locking the legitimate player out.  This test pins
     * {@code uq_trick_play_trick_seat} as the <em>only</em> constraint that fires in
     * this scenario, and asserts the constraint by name.
     *
     * <h3>What this pins that the concurrent sibling ({@link #concurrentDoublePlayForSameSeatIsRejected})
     * cannot</h3>
     * <p>The concurrent test uses one player racing itself: the losing row violates both
     * {@code uq_trick_play_trick_seat} and {@code uq_trick_play_trick_player}
     * simultaneously, so the engine may report either constraint name.  That test
     * deliberately asserts only SQL state {@code 23505} to avoid reintroducing the flake
     * that plagued the two-player form.  This test isolates {@code uq_trick_play_trick_seat}
     * as the <em>only</em> violable constraint by using two different players, so the
     * constraint name in the exception message is deterministic and can be asserted.
     *
     * <h3>Why the scenario is constructible at all</h3>
     * <p>Seats are numbered from 0 within each session.  Alice holds seat 2 in session A;
     * Eve holds seat 2 in session B.  Both satisfy {@code fk_trick_play_player_seat}
     * ({@code (player_id, seat_order) → player(id, seat_order)}) because each player
     * genuinely holds seat 2 on <em>some</em> {@code player} row.  The composite foreign
     * key proves the pair exists on <em>some</em> player row, not that the player is at
     * <em>this</em> trick's table.  Cross-session containment is deliberately absent from
     * storage and deferred to Slice C's play use case — see the deferral section in
     * ADR-023.
     *
     * <h3>Documented behaviour, not a defect to fix here</h3>
     * <p>This scenario is simultaneously the residual attack that @architecture-guardian
     * identified: a cross-session attacker at the same seat number can take session A's
     * seat-2 occupant out of play.  It is recorded here as a test rather than fixed,
     * because fixing it in storage requires {@code game_session_id} denormalised onto
     * {@code trick_play} — a copy that can disagree with its parent — and ADR-023 defers
     * that to Slice C.
     *
     * <h3>If Slice C ever adds cross-session enforcement in storage</h3>
     * <p><strong>If Slice C adds a foreign key or check constraint that scopes a play to
     * the session that owns its trick, this test's setup becomes unrepresentable: Eve's
     * insert will be rejected by the new cross-session constraint before it reaches
     * {@code uq_trick_play_trick_seat}.  This test must be rewritten rather than
     * deleted.</strong>  The scenario it documents — a cross-session player locking out a
     * legitimate seat — remains a real attack surface until Slice C closes it; the test
     * should survive in some form that records the closure.
     *
     * <h3>Why naming the constraint is safe here</h3>
     * <p>The scenario admits exactly one violable constraint.  Walk each candidate:
     * <ul>
     *   <li>{@code fk_trick_play_trick} — satisfied: the trick exists in session A.</li>
     *   <li>{@code fk_trick_play_card} — satisfied: both cards are in the seeded catalogue.</li>
     *   <li>{@code fk_trick_play_player_seat} — satisfied by <em>both</em> players: Alice
     *       holds {@code (aliceId, 2)} in session A; Eve holds {@code (eveId, 2)} in session B.
     *       The composite key checks only that the pair resolves in the {@code player} table,
     *       not that the player belongs to the trick's session.</li>
     *   <li>{@code uq_trick_play_trick_player} — not violated: Alice and Eve have different
     *       {@code player_id} values, so the pair {@code (trickId, eveId)} is unique.</li>
     *   <li>{@code uq_trick_play_trick_card} — not violated: the two plays use different
     *       cards.</li>
     *   <li>{@code uq_trick_play_trick_seat} — <strong>violated</strong>: both plays claim
     *       {@code (trickId, 2)}, and Alice's play is already committed.  This is the only
     *       constraint the second insert can violate, so the engine must report it, and the
     *       constraint name in the exception message is deterministic.</li>
     * </ul>
     *
     * <p>Contrast with the concurrent sibling, where the losing row violates both
     * {@code uq_trick_play_trick_seat} and {@code uq_trick_play_trick_player} and the
     * engine may report either — which is why that test asserts only SQL state, not the
     * constraint name.
     *
     * <p>Empirically verified: H2 2.4.240 produces the message
     * {@code Unique index or primary key violation: "PUBLIC.UQ_TRICK_PLAY_TRICK_SEAT
     * INDEX PUBLIC.UQ_TRICK_PLAY_TRICK_SEAT_INDEX_A ON PUBLIC.TRICK_PLAY(TRICK_ID NULLS
     * FIRST, SEAT_ORDER NULLS FIRST) VALUES ( /* key:1 *&#47; UUID '...', 2)"}.
     * The constraint name {@code UQ_TRICK_PLAY_TRICK_SEAT} appears verbatim in the message.
     */
    @Test
    @DisplayName("cross-session player at the same seat number locks out the legitimate occupant — rejected by uq_trick_play_trick_seat")
    void secondPlayAtSameSeatByCrossSessionPlayerIsRejected() throws Exception {
        // Arrange — two sessions, two players each holding seat 2, one trick in session A

        // Session A: Alice at seat 2
        final UUID sessionA = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID aliceId = MigrationTestFixtures.insertPlayerInSession(connection, sessionA, 2);

        // Session B: Eve at seat 2 (same seat number, different session — cross-session attacker)
        final UUID sessionB = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID eveId = MigrationTestFixtures.insertPlayerInSession(connection, sessionB, 2);

        // Trick belongs to session A
        final UUID trickId = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionA);

        // Two different cards — uq_trick_play_trick_card must not fire
        final UUID aliceCard = MigrationTestFixtures.anyExistingCard(connection);
        final UUID eveCard = MigrationTestFixtures.secondExistingCard(connection);

        // Non-vacuity: Alice's play must succeed before Eve's is attempted.
        // If the schema were rejecting everything (e.g. a broken FK chain), this assertion
        // would fail and expose the false negative before the constraint assertion is reached.
        MigrationTestFixtures.insertTrickPlay(connection, trickId, aliceCard, 2, aliceId);

        // Act + Assert — Eve attempts to play at seat 2 in session A's trick.
        // Only uq_trick_play_trick_seat can fire here (see Javadoc for the full constraint walk).
        // Naming the constraint is safe in this scenario; the concurrent sibling deliberately
        // does not name one because its losing row violates two constraints simultaneously.
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlay(connection, trickId, eveCard, 2, eveId))
                .as("Eve (session B, seat 2) playing into session A's trick at seat 2 must be "
                        + "rejected by uq_trick_play_trick_seat (SQL state 23505, constraint name in message)")
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .satisfies(e -> {
                    final SQLException sqle = (SQLException) e;
                    assertThat(sqle.getSQLState())
                            .as("SQL state must be 23505 (unique violation)")
                            .isEqualTo(SQL_STATE_UNIQUE_VIOLATION);
                    // The constraint name is deterministic here because uq_trick_play_trick_seat
                    // is the only constraint the second insert can violate (see Javadoc).
                    // H2 2.4.240 includes the constraint name verbatim in the exception message.
                    assertThat(sqle.getMessage())
                            .as("H2 exception message must name the violated constraint")
                            .containsIgnoringCase("UQ_TRICK_PLAY_TRICK_SEAT");
                });
    }
}
