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
 * Proves that the composite primary key on {@code hand_card} is enforced by the
 * database engine, not merely declared.
 *
 * <p>The constraint under test, read directly from
 * {@code 004-trick-play-schema.xml} changeset 003:
 * {@code pk_hand_card} on {@code (hand_id, card_id)}.
 *
 * <p>The changeset comment states: "PRIMARY KEY (hand_id, card_id) is the identity
 * and simultaneously enforces the invariant that matters: a card cannot appear twice
 * in the same hand." This test verifies that claim is true at the database level.
 *
 * <p>No Spring context. Owns its own uniquely-named in-memory H2 database.
 * {@code connection.setAutoCommit(true)} is set immediately after Liquibase runs.
 */
@DisplayName("pk_hand_card prevents the same card appearing twice in the same hand")
class HandCardPrimaryKeyTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:hand-card-pk;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /**
     * SQL state H2 emits on a primary-key violation.
     * Verified empirically: H2 2.4.240 throws
     * {@code org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException} with
     * {@code getSQLState() == "23505"} for both unique and primary-key violations.
     */
    private static final String SQL_STATE_PK_VIOLATION = "23505";

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
        // Re-enable it so that rows inserted in the test body are immediately committed.
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

    /**
     * Inserting the same {@code (hand_id, card_id)} pair twice must be rejected by
     * {@code pk_hand_card} with SQL state {@code 23505}.
     *
     * <p>This is the storage-level enforcement of the invariant that a card cannot
     * appear twice in the same hand. The changeset comment claims this PK is the
     * no-duplicate-card-in-a-hand invariant; this test verifies that claim.
     */
    @Test
    @DisplayName("inserting the same (hand_id, card_id) twice is rejected by pk_hand_card (23505)")
    void duplicateCardInSameHandIsRejected() throws Exception {
        // Arrange — a session, a player, a hand, and a card
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID playerId = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);
        final UUID handId = MigrationTestFixtures.insertMinimalHand(connection, sessionId, playerId, 0);
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);

        // First insert — must succeed
        MigrationTestFixtures.insertHandCard(connection, handId, cardId);

        // Act + Assert — second insert of the same (hand_id, card_id) must fail
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertHandCard(connection, handId, cardId))
                .as("inserting (hand_id=%s, card_id=%s) twice must be rejected "
                        + "by pk_hand_card with SQL state %s",
                        handId, cardId, SQL_STATE_PK_VIOLATION)
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_PK_VIOLATION);
    }
}
