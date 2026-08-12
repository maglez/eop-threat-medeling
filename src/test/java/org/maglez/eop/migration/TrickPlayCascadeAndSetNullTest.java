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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Proves that deleting a {@code game_session} row cascades cleanly through every
 * child table, and that the {@code fk_trick_winner_play} SET NULL behaviour works
 * as specified.
 *
 * <p>Two concerns are tested here because they share the same FK graph and the same
 * full-fan-out fixture:
 *
 * <ol>
 *   <li><strong>Session-delete cascade</strong> (auditor D6): a session with two players,
 *       a hand with hand_cards, a trick, plays, components, and a resolved trick pointing
 *       at a winning play must be deletable in a single {@code DELETE FROM game_session}
 *       statement, leaving every child table empty for that session.</li>
 *   <li><strong>fk_trick_winner_play SET NULL</strong> (auditor D3 regression): deleting a
 *       winning play while its trick survives must clear {@code trick.winner_play_id} to
 *       NULL rather than being refused. Also confirms that deleting a resolved trick now
 *       succeeds — this is the exact defect that NO ACTION caused (23503) and that SET NULL
 *       was added to fix.</li>
 * </ol>
 *
 * <p>No Spring context. Owns its own uniquely-named in-memory H2 database.
 * {@code connection.setAutoCommit(true)} is set immediately after Liquibase runs.
 */
@DisplayName("session-delete cascade and fk_trick_winner_play SET NULL behaviour")
class TrickPlayCascadeAndSetNullTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:trick-play-cascade;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

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
        // Re-enable it so that every insert is immediately committed and visible to the
        // DELETE statement that follows.
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
    // Test 1 — session-delete cascade clears every child table (auditor D6)
    // =========================================================================

    /**
     * Builds a full fan-out under one session — two players, a hand with two hand_cards,
     * a trick, two plays, two components, and a resolved trick pointing at a winning play —
     * then deletes the session and asserts every child table's count for that session is 0.
     *
     * <p>If the delete throws, the test fails immediately with the SQL state and constraint
     * name from the exception message, which is the BLOCKER signal requested in the brief.
     */
    @Test
    @DisplayName("DELETE FROM game_session cascades to hand, hand_card, trick, trick_play, trick_play_component")
    void sessionDeleteCascadesToAllChildTables() throws Exception {
        // Arrange — session
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);

        // Arrange — two players in the session
        final UUID player1Id = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);
        final UUID player2Id = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 1);

        // Arrange — a hand for player 1 with two cards
        final UUID handId = MigrationTestFixtures.insertMinimalHand(connection, sessionId, player1Id, 0);
        final UUID card1Id = MigrationTestFixtures.anyExistingCard(connection);
        final UUID card2Id = MigrationTestFixtures.secondExistingCard(connection);
        MigrationTestFixtures.insertHandCard(connection, handId, card1Id);
        MigrationTestFixtures.insertHandCard(connection, handId, card2Id);

        // Arrange — a trick in the session
        final UUID trickId = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId);

        // Arrange — two plays in the trick (one per player, one per card)
        final UUID play1Id = MigrationTestFixtures.insertTrickPlay(connection, trickId, card1Id, 0, player1Id);
        final UUID play2Id = MigrationTestFixtures.insertTrickPlay(connection, trickId, card2Id, 1, player2Id);

        // Arrange — two components on play 1
        MigrationTestFixtures.insertTrickPlayComponent(connection, play1Id, 0, "Component A");
        MigrationTestFixtures.insertTrickPlayComponent(connection, play1Id, 1, "Component B");

        // Arrange — resolve the trick: point winner_play_id at play 1
        setWinnerPlay(connection, trickId, play1Id);

        // Verify the fan-out exists before the delete
        assertThat(countRowsForSession(connection, "hand", sessionId))
                .as("hand rows must exist before session delete").isEqualTo(1);
        assertThat(countRowsForSession(connection, "trick", sessionId))
                .as("trick rows must exist before session delete").isEqualTo(1);

        // Act — delete the session; if this throws, report the SQL state immediately
        try {
            deleteGameSession(connection, sessionId);
        } catch (SQLException e) {
            fail("DELETE FROM game_session threw SQLException — this is a BLOCKER. "
                    + "SQL state: " + e.getSQLState()
                    + ", message: " + e.getMessage());
        }

        // Assert — every child table is empty for this session
        assertThat(countRowsForSession(connection, "hand", sessionId))
                .as("hand rows for session %s must be 0 after session delete", sessionId)
                .isEqualTo(0);
        assertThat(countHandCardRowsForSession(connection, sessionId))
                .as("hand_card rows for session %s must be 0 after session delete", sessionId)
                .isEqualTo(0);
        assertThat(countRowsForSession(connection, "trick", sessionId))
                .as("trick rows for session %s must be 0 after session delete", sessionId)
                .isEqualTo(0);
        assertThat(countTrickPlayRowsForSession(connection, sessionId))
                .as("trick_play rows for session %s must be 0 after session delete", sessionId)
                .isEqualTo(0);
        assertThat(countTrickPlayComponentRowsForSession(connection, sessionId))
                .as("trick_play_component rows for session %s must be 0 after session delete", sessionId)
                .isEqualTo(0);
    }

    // =========================================================================
    // Test 2 — fk_trick_winner_play SET NULL: deleting a winning play nulls the column
    // =========================================================================

    /**
     * Deleting a winning play while its trick survives must clear
     * {@code trick.winner_play_id} to NULL rather than being refused.
     *
     * <p>This is the SET NULL behaviour added to fix the NO ACTION defect (23503).
     */
    @Test
    @DisplayName("deleting a winning play while its trick survives sets trick.winner_play_id to NULL (SET NULL)")
    void deletingWinningPlayNullsWinnerPlayId() throws Exception {
        // Arrange — session, player, trick, play, resolve
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID playerId = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId);
        final UUID playId = MigrationTestFixtures.insertTrickPlay(connection, trickId, cardId, 0, playerId);
        setWinnerPlay(connection, trickId, playId);

        // Verify the trick is resolved before the delete
        assertThat(winnerPlayId(connection, trickId))
                .as("trick.winner_play_id must be set before the play is deleted")
                .isEqualTo(playId);

        // Act — delete the winning play directly (not via session cascade)
        deleteTrickPlay(connection, playId);

        // Assert — the trick still exists
        assertThat(trickExists(connection, trickId))
                .as("trick %s must still exist after its winning play was deleted", trickId)
                .isTrue();

        // Assert — winner_play_id is now NULL
        assertThat(winnerPlayId(connection, trickId))
                .as("trick.winner_play_id must be NULL after the winning play was deleted (SET NULL)")
                .isNull();
    }

    // =========================================================================
    // Test 3 — fk_trick_winner_play regression: deleting a resolved trick succeeds
    // =========================================================================

    /**
     * Deleting a resolved trick (one with a non-null {@code winner_play_id}) must succeed.
     *
     * <p>This is the exact defect that NO ACTION caused: H2 rejected the delete with
     * {@code 23503} because the row-at-a-time check saw the trick still pointing at a play
     * the cascade had just removed. SET NULL was added to fix this. This test is a
     * regression guard: if the FK is ever changed back to NO ACTION, this test fails.
     */
    @Test
    @DisplayName("deleting a resolved trick succeeds — regression guard for the NO ACTION / 23503 defect")
    void deletingResolvedTrickSucceeds() throws Exception {
        // Arrange — session, player, trick, play, resolve
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID playerId = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId);
        final UUID playId = MigrationTestFixtures.insertTrickPlay(connection, trickId, cardId, 0, playerId);
        setWinnerPlay(connection, trickId, playId);

        // Verify the trick is resolved before the delete
        assertThat(winnerPlayId(connection, trickId))
                .as("trick.winner_play_id must be set before the trick is deleted")
                .isEqualTo(playId);

        // Act — delete the trick (cascades to trick_play, which cascades to trick_play_component)
        // Under NO ACTION this threw 23503; under SET NULL it must succeed.
        try {
            deleteTrick(connection, trickId);
        } catch (SQLException e) {
            fail("DELETE FROM trick threw SQLException — this is a regression of the NO ACTION / 23503 defect. "
                    + "SQL state: " + e.getSQLState()
                    + ", message: " + e.getMessage());
        }

        // Assert — the trick is gone
        assertThat(trickExists(connection, trickId))
                .as("trick %s must not exist after being deleted", trickId)
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void setWinnerPlay(
            final Connection conn,
            final UUID trickId,
            final UUID playId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE trick SET winner_play_id = ? WHERE id = ?")) {
            ps.setObject(1, playId);
            ps.setObject(2, trickId);
            ps.executeUpdate();
        }
    }

    private static void deleteGameSession(
            final Connection conn,
            final UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM game_session WHERE id = ?")) {
            ps.setObject(1, sessionId);
            ps.executeUpdate();
        }
    }

    private static void deleteTrickPlay(
            final Connection conn,
            final UUID playId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM trick_play WHERE id = ?")) {
            ps.setObject(1, playId);
            ps.executeUpdate();
        }
    }

    private static void deleteTrick(
            final Connection conn,
            final UUID trickId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM trick WHERE id = ?")) {
            ps.setObject(1, trickId);
            ps.executeUpdate();
        }
    }

    private static UUID winnerPlayId(
            final Connection conn,
            final UUID trickId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT winner_play_id FROM trick WHERE id = ?")) {
            ps.setObject(1, trickId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("trick %s must exist when reading winner_play_id", trickId)
                        .isTrue();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static boolean trickExists(
            final Connection conn,
            final UUID trickId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM trick WHERE id = ?")) {
            ps.setObject(1, trickId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Counts rows in a table that has a {@code game_session_id} column. */
    private static int countRowsForSession(
            final Connection conn,
            final String tableName,
            final UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + tableName + " WHERE game_session_id = ?")) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Counts {@code hand_card} rows for a session by joining through {@code hand}.
     * {@code hand_card} has no {@code game_session_id} column.
     */
    private static int countHandCardRowsForSession(
            final Connection conn,
            final UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM hand_card hc "
                        + "JOIN hand h ON hc.hand_id = h.id "
                        + "WHERE h.game_session_id = ?")) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Counts {@code trick_play} rows for a session by joining through {@code trick}.
     * {@code trick_play} has no {@code game_session_id} column.
     */
    private static int countTrickPlayRowsForSession(
            final Connection conn,
            final UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM trick_play tp "
                        + "JOIN trick t ON tp.trick_id = t.id "
                        + "WHERE t.game_session_id = ?")) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Counts {@code trick_play_component} rows for a session by joining through
     * {@code trick_play} and {@code trick}.
     * {@code trick_play_component} has no {@code game_session_id} column.
     */
    private static int countTrickPlayComponentRowsForSession(
            final Connection conn,
            final UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM trick_play_component tpc "
                        + "JOIN trick_play tp ON tpc.trick_play_id = tp.id "
                        + "JOIN trick t ON tp.trick_id = t.id "
                        + "WHERE t.game_session_id = ?")) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
