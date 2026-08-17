package org.maglez.eop.migration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the 2026-08-17--trim-deck-to-74-printed-cards.xml migration round-trips correctly:
 * forward migration removes the four absent cards; rollback re-inserts them; and re-applying the
 * migration removes them again.
 *
 * <p>No Spring context. This is a Liquibase-API test that needs full isolation over its own
 * database, not context verification. Using {@code @SpringBootTest} here would share the suite's
 * {@code eop-test} datasource, which Spring's Liquibase has already migrated — so {@code update()}
 * would find nothing pending and the rollback would target the wrong changeset.
 *
 * <p>The database name is unique per test class to avoid collisions with the suite's
 * {@code eop-test} database or with parallel runs.
 *
 * <p>The four card IDs are taken directly from {@code 002-real-deck.xml} — the same source the
 * changeset uses — so the test cannot accidentally assert the wrong rows.
 */
@DisplayName("2026-08-17--trim-deck-to-74-printed-cards migration round-trip")
class DeckTrimMigrationRoundTripTest {

    /** Unique in-memory database name — does not collide with the suite's eop-test. */
    private static final String JDBC_URL =
            "jdbc:h2:mem:deck-trim-roundtrip;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /** Total card count after the full migration (78 seeded − 4 trimmed). */
    private static final int DECK_SIZE_AFTER_TRIM = 74;

    /** Total card count before the trim migration (seeded by 002-real-deck.xml). */
    private static final int DECK_SIZE_BEFORE_TRIM = 78;

    /**
     * The four card IDs removed by the trim migration.
     * Sourced from 002-real-deck.xml lines 214, 634, 642, 650.
     */
    private static final Set<String> TRIMMED_CARD_IDS = Set.of(
            "0145d70f-544b-5cd0-9c0a-164d09a969b7",  // TAMPERING rank 2
            "24b333fb-38c4-581e-b86c-670cdc7b4c62",  // ELEVATION_OF_PRIVILEGE rank 2
            "e030b625-b4e3-51d4-9d91-1b50ae0bf0e1",  // ELEVATION_OF_PRIVILEGE rank 3
            "7229e28b-c578-5ab8-bbc5-fdc8d5b00521"   // ELEVATION_OF_PRIVILEGE rank 4
    );

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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (liquibase != null) {
            liquibase.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        // Drop the in-memory database so the next test run starts clean.
        try (Connection dropConn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var stmt = dropConn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS DELETE FILES");
        } catch (SQLException ignored) {
            // Best-effort cleanup; H2 mem databases are discarded when the last connection closes.
        }
    }

    @Test
    @DisplayName("forward migration removes the four absent cards, leaving 74 in the deck")
    void forwardMigrationTrimsToSeventyFourCards() throws Exception {
        // Arrange — database is empty; Liquibase has not run yet.

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — total card count is 74
        assertThat(countCards(connection))
                .as("deck must hold exactly 74 cards after the trim migration")
                .isEqualTo(DECK_SIZE_AFTER_TRIM);

        // Assert — none of the four trimmed cards are present
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must be absent after the trim migration", id)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("rollback of the trim migration re-inserts the four cards, restoring 78")
    void rollbackRestoresSeventyEightCards() throws Exception {
        // Arrange — apply the full migration first
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Act — roll back the single trim changeset
        liquibase.rollback(1, new Contexts(), new LabelExpression());

        // Assert — total card count is back to 78
        assertThat(countCards(connection))
                .as("deck must hold exactly 78 cards after rolling back the trim migration")
                .isEqualTo(DECK_SIZE_BEFORE_TRIM);

        // Assert — all four trimmed cards are present again
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must be present after rolling back the trim migration", id)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("re-applying the trim migration after rollback removes the four cards again")
    void reapplyAfterRollbackTrimsAgain() throws Exception {
        // Arrange — apply, roll back, then apply again
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(1, new Contexts(), new LabelExpression());

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — total card count is 74 again
        assertThat(countCards(connection))
                .as("deck must hold exactly 74 cards after re-applying the trim migration")
                .isEqualTo(DECK_SIZE_AFTER_TRIM);

        // Assert — none of the four trimmed cards are present
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must be absent after re-applying the trim migration", id)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("precondition HALT fires when hand_card references a trimmed card — migration refuses and cards survive")
    void preconditionHaltsWhenHandCardReferencesATrimmedCard() throws Exception {
        // Arrange — apply the full migration (74 cards), then roll back the trim (78 cards).
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(1, new Contexts(), new LabelExpression());

        // Insert a hand_card row referencing one of the trimmed cards.
        // Disable FK checks so we can insert without satisfying the hand → game_session → player chain.
        final String trimmedCardId = TRIMMED_CARD_IDS.iterator().next();
        final String handId = "aaaaaaaa-0000-0000-0000-000000000001";
        try (var stmt = connection.createStatement()) {
            stmt.execute("SET REFERENTIAL_INTEGRITY FALSE");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO hand_card (hand_id, card_id) VALUES (?, ?)")) {
            ps.setObject(1, java.util.UUID.fromString(handId));
            ps.setObject(2, java.util.UUID.fromString(trimmedCardId));
            ps.executeUpdate();
        }
        connection.commit();

        // Act + Assert — re-applying the trim must throw because the precondition detects the reference.
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
                .isInstanceOf(LiquibaseException.class)
                .hasMessageContaining("Cannot trim deck: removed cards still referenced by hand_card or trick_play");

        // Assert — the trimmed card still exists (migration was refused, not partially applied).
        assertThat(cardExists(connection, trimmedCardId))
                .as("trimmed card must still exist after the precondition HALT")
                .isTrue();
        assertThat(countCards(connection))
                .as("deck must still hold 78 cards after the precondition HALT")
                .isEqualTo(DECK_SIZE_BEFORE_TRIM);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the total number of rows in the {@code card} table.
     */
    private static int countCards(final Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM card");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next())
                    .as("card table must be queryable after update()")
                    .isTrue();
            return rs.getInt(1);
        }
    }

    /**
     * Returns {@code true} if a row with the given UUID exists in the {@code card} table.
     *
     * @param id the UUID string to look up
     */
    private static boolean cardExists(final Connection conn, final String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM card WHERE id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("card existence query must return a result row")
                        .isTrue();
                return rs.getInt(1) > 0;
            }
        }
    }
}
