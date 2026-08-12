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
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the unique constraints on {@code hand} and {@code trick} are enforced
 * by the database engine, not merely declared.
 *
 * <p>The two constraints under test, read directly from
 * {@code 004-trick-play-schema.xml}:
 * <ul>
 *   <li>{@code uq_hand_session_seat} (changeset 002) — two hands in the same session
 *       cannot occupy the same seat. Declared since the first draft; never tested until
 *       now (guardian MAJOR-9).</li>
 *   <li>{@code uq_trick_session_sequence} (changeset 004) — two tricks in the same
 *       session cannot share the same sequence number. Declared since the first draft;
 *       never tested until now.</li>
 * </ul>
 *
 * <p>No Spring context. Owns its own uniquely-named in-memory H2 database.
 * {@code connection.setAutoCommit(true)} is set immediately after Liquibase runs.
 */
@DisplayName("uq_hand_session_seat and uq_trick_session_sequence are enforced by the database")
class HandAndTrickUniqueConstraintTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:hand-trick-unique;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /**
     * SQL state H2 emits on a unique-index or primary-key violation.
     * Verified empirically: H2 2.4.240 throws
     * {@code org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException} with
     * {@code getSQLState() == "23505"}.
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
        // Re-enable it so that rows inserted in the test body are immediately committed and
        // visible to subsequent inserts on the same connection.
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
    // Test 1 — uq_hand_session_seat: two hands in the same session, same seat
    // =========================================================================

    /**
     * Two {@code hand} rows in the same session with the same {@code seat_order} must be
     * rejected by {@code uq_hand_session_seat} with SQL state {@code 23505}.
     */
    @Test
    @DisplayName("two hands in the same session at the same seat are rejected by uq_hand_session_seat (23505)")
    void twoHandsInSameSessionAtSameSeatAreRejected() throws Exception {
        // Arrange — the second player is deliberately in a DIFFERENT session, holding the
        // SAME seat number. Two players in one session cannot both hold seat 0
        // (uq_player_session_seat), so the obvious setup — a second player at seat 1 whose
        // hand claims seat 0 — violates fk_hand_player_seat as well as the constraint under
        // test. H2 reports the unique violation first, so such a test passes here and would
        // fail on PostgreSQL 17, where evaluation order is not guaranteed to agree. Seats
        // are numbered from 0 in every session, so a seat-0 player in session B satisfies
        // fk_hand_player_seat while colliding only on uq_hand_session_seat, whose columns
        // are (game_session_id, seat_order). That leaves exactly one constraint able to fire
        // on either engine.
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID otherSessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID player1Id = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);
        final UUID player2Id =
                MigrationTestFixtures.insertMinimalPlayer(connection, otherSessionId, 0);

        // First hand at seat 0 — must succeed. Unwrapped, so a schema that rejected
        // everything would error here rather than reaching the assertion below.
        MigrationTestFixtures.insertMinimalHand(connection, sessionId, player1Id, 0);

        // Act + Assert — second hand in the same session at the same seat must fail
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertMinimalHand(connection, sessionId, player2Id, 0))
                .as("two hands in session %s at seat 0 must be rejected "
                        + "by uq_hand_session_seat with SQL state %s",
                        sessionId, SQL_STATE_UNIQUE_VIOLATION)
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_UNIQUE_VIOLATION);
    }

    // =========================================================================
    // Test 2 — uq_trick_session_sequence: two tricks in the same session, same sequence
    // =========================================================================

    /**
     * Two {@code trick} rows in the same session with the same {@code sequence} must be
     * rejected by {@code uq_trick_session_sequence} with SQL state {@code 23505}.
     */
    @Test
    @DisplayName("two tricks in the same session with the same sequence are rejected by uq_trick_session_sequence (23505)")
    void twoTricksInSameSessionWithSameSequenceAreRejected() throws Exception {
        // Arrange — one session, first trick at sequence 1 — must succeed
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId);

        // Act + Assert — second trick in the same session with sequence 1 must fail
        // insertMinimalTrickInSession always uses sequence=1, so the second call collides.
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId))
                .as("two tricks in session %s with sequence 1 must be rejected "
                        + "by uq_trick_session_sequence with SQL state %s",
                        sessionId, SQL_STATE_UNIQUE_VIOLATION)
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_UNIQUE_VIOLATION);
    }
}
