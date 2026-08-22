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
import java.util.Map;
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
 *
 * <p>Note: the 2026-08-18--remove-ace-cards.xml migration runs after the trim migration, so the
 * full migration chain produces 68 cards. Rolling back 1 changeset undoes the ace removal (68→74);
 * rolling back 2 changesets undoes both the ace removal and the trim (74→78).
 */
@DisplayName("2026-08-17--trim-deck-to-74-printed-cards migration round-trip")
class DeckTrimMigrationRoundTripTest {

    /** Unique in-memory database name — does not collide with the suite's eop-test. */
    private static final String JDBC_URL =
            "jdbc:h2:mem:deck-trim-roundtrip;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /** Total card count after the full migration chain (78 seeded − 4 trimmed − 6 aces removed). */
    private static final int DECK_SIZE_AFTER_ALL_MIGRATIONS = 68;

    /** Total card count after rolling back the ace-removal migration (68 + 6 aces restored). */
    private static final int DECK_SIZE_AFTER_TRIM_ONLY = 74;

    /** Total card count before the trim migration (seeded by 002-real-deck.xml). */
    private static final int DECK_SIZE_BEFORE_TRIM = 78;

    /**
     * The changelog that removes the aces, and the number of changesets it contributes.
     *
     * <p>Rollback depth is computed from these rather than hardcoded, because {@code <includeAll>}
     * orders {@code changes/} alphabetically and any later dated changelog is applied after these
     * two. A literal {@code rollback(1)} silently rolled back the wrong changeset the first time a
     * migration was appended (EOP-24 widened the join code and broke exactly that assumption).</p>
     */
    private static final String CHANGELOG_ACE_REMOVAL = "2026-08-18--remove-ace-cards.xml";

    /** Changesets in {@link #CHANGELOG_ACE_REMOVAL} — the floor for a rollback that reaches it. */
    private static final int ACE_REMOVAL_CHANGESETS = 1;

    /** The changelog that trims the deck to 74 cards; rolling back to it also undoes the ace removal. */
    private static final String CHANGELOG_DECK_TRIM = "2026-08-17--trim-deck-to-74-printed-cards.xml";

    /**
     * Floor for a rollback that reaches {@link #CHANGELOG_DECK_TRIM}: its own changeset plus the ace
     * removal that follows it. This is a lower bound asserted by the helper, not the depth actually
     * used — {@link #changesetsAppliedFrom} counts what {@code DATABASECHANGELOG} really holds, which
     * today is larger because the join-code widening was appended after both deck changelogs.
     */
    private static final int DECK_TRIM_ONWARDS_CHANGESETS = 2;

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

    /**
     * The six Ace card IDs removed by the ace-removal migration.
     * Sourced from 002-real-deck.xml lines 205, 310, 415, 520, 625, 730.
     */
    private static final Set<String> ACE_CARD_IDS = Set.of(
            "048c87ce-e1e0-519b-be26-05f7b8ae9e5e",  // SPOOFING rank 14
            "56c1a22f-b191-5a09-8231-faca8224ff2c",  // TAMPERING rank 14
            "089d68f1-0588-5840-a25f-5f6157731327",  // REPUDIATION rank 14
            "4d22c229-2edb-56eb-8983-28d885d1b9a8",  // INFORMATION_DISCLOSURE rank 14
            "59f51c4e-1887-5614-a9c9-c3916f2cfd86",  // DENIAL_OF_SERVICE rank 14
            "2a497b0e-e59d-50c9-a24b-f03f347dd4ed"   // ELEVATION_OF_PRIVILEGE rank 14
    );

    /**
     * The correct threat_prompt values for each Ace card, as seeded by 002-real-deck.xml.
     * These are the values the rollback must restore exactly.
     */
    private static final Map<String, String> ACE_THREAT_PROMPTS = Map.of(
            "048c87ce-e1e0-519b-be26-05f7b8ae9e5e", "You've invented a new Spoofing attack",
            "56c1a22f-b191-5a09-8231-faca8224ff2c", "You've invented a new Tampering attack",
            "089d68f1-0588-5840-a25f-5f6157731327", "You've invented a new Repudiation attack",
            "4d22c229-2edb-56eb-8983-28d885d1b9a8", "You've invented a new Information Disclosure attack",
            "59f51c4e-1887-5614-a9c9-c3916f2cfd86", "You've invented a new Denial of Service attack",
            "2a497b0e-e59d-50c9-a24b-f03f347dd4ed", "You've invented a new Elevation of Privilege attack"
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
    @DisplayName("forward migration removes the four absent cards, leaving 74 in the deck (before ace removal)")
    void forwardMigrationTrimsToSeventyFourCards() throws Exception {
        // Arrange — database is empty; Liquibase has not run yet.

        // Act — apply all migrations (trim + ace removal)
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — total card count is 68 (74 after trim, then 6 aces removed)
        assertThat(countCards(connection))
                .as("deck must hold exactly 68 cards after all migrations (trim + ace removal)")
                .isEqualTo(DECK_SIZE_AFTER_ALL_MIGRATIONS);

        // Assert — none of the four trimmed cards are present
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must be absent after the trim migration", id)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("rollback of the ace-removal migration restores 6 aces, giving 74 cards")
    void rollbackRestoresSeventyEightCards() throws Exception {
        // Arrange — apply the full migration first
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Act — roll back to the ace removal inclusive, leaving the trim in place. The depth is
        // computed, so changelogs appended after the ace removal are unwound too.
        liquibase.rollback(changesetsAppliedFrom(connection, CHANGELOG_ACE_REMOVAL, ACE_REMOVAL_CHANGESETS),
                new Contexts(), new LabelExpression());

        // Assert — total card count is back to 74 (trim applied, aces restored)
        assertThat(countCards(connection))
                .as("deck must hold exactly 74 cards after rolling back the ace-removal migration")
                .isEqualTo(DECK_SIZE_AFTER_TRIM_ONLY);

        // Assert — all four trimmed cards are still absent (trim migration still applied)
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must still be absent after rolling back only the ace-removal migration", id)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("rollback of both migrations restores all 78 original cards")
    void rollbackBothMigrationsRestoresSeventyEightCards() throws Exception {
        // Arrange — apply the full migration first
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Act — roll back to the trim inclusive, which also unwinds the ace removal above it and
        // anything appended after either.
        liquibase.rollback(changesetsAppliedFrom(connection, CHANGELOG_DECK_TRIM, DECK_TRIM_ONWARDS_CHANGESETS),
                new Contexts(), new LabelExpression());

        // Assert — total card count is back to 78
        assertThat(countCards(connection))
                .as("deck must hold exactly 78 cards after rolling back both migrations")
                .isEqualTo(DECK_SIZE_BEFORE_TRIM);

        // Assert — all four trimmed cards are present again
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must be present after rolling back both migrations", id)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("re-applying all migrations after rollback produces 68 cards again")
    void reapplyAfterRollbackTrimsAgain() throws Exception {
        // Arrange — apply, roll back both, then apply again
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(changesetsAppliedFrom(connection, CHANGELOG_DECK_TRIM, DECK_TRIM_ONWARDS_CHANGESETS),
                new Contexts(), new LabelExpression());

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — total card count is 68 again
        assertThat(countCards(connection))
                .as("deck must hold exactly 68 cards after re-applying all migrations")
                .isEqualTo(DECK_SIZE_AFTER_ALL_MIGRATIONS);

        // Assert — none of the four trimmed cards are present
        for (final String id : TRIMMED_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("card %s must be absent after re-applying all migrations", id)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("precondition HALT fires when hand_card references a trimmed card — migration refuses and cards survive")
    void preconditionHaltsWhenHandCardReferencesATrimmedCard() throws Exception {
        // Arrange — apply the full migration (68 cards), then roll back both migrations (78 cards).
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(changesetsAppliedFrom(connection, CHANGELOG_DECK_TRIM, DECK_TRIM_ONWARDS_CHANGESETS),
                new Contexts(), new LabelExpression());

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

    @Test
    @DisplayName("ace-removal rollback restores all 6 Ace IDs and their correct threat_prompt values")
    void aceRollbackRestoresCorrectThreatPrompts() throws Exception {
        // Arrange — apply the full migration (68 cards)
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — all six Ace IDs are absent after the forward migration
        for (final String id : ACE_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("Ace card %s must be absent after the ace-removal migration", id)
                    .isFalse();
        }

        // Act — roll back to the ace removal inclusive, computed rather than a literal depth.
        liquibase.rollback(changesetsAppliedFrom(connection, CHANGELOG_ACE_REMOVAL, ACE_REMOVAL_CHANGESETS),
                new Contexts(), new LabelExpression());

        // Assert — all six Ace IDs are present again
        for (final String id : ACE_CARD_IDS) {
            assertThat(cardExists(connection, id))
                    .as("Ace card %s must be present after rolling back the ace-removal migration", id)
                    .isTrue();
        }

        // Assert — each restored Ace has the correct threat_prompt (not a duplicate of a live row)
        for (final Map.Entry<String, String> entry : ACE_THREAT_PROMPTS.entrySet()) {
            final String actualPrompt = readThreatPrompt(connection, entry.getKey());
            assertThat(actualPrompt)
                    .as("Ace card %s must have threat_prompt '%s' after rollback", entry.getKey(), entry.getValue())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("ace-removal precondition HALT fires when hand_card references an Ace card")
    void aceRemovalPreconditionHaltsWhenHandCardReferencesAnAce() throws Exception {
        // Arrange — apply the full migration (68 cards), then roll back both (78 cards).
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(changesetsAppliedFrom(connection, CHANGELOG_DECK_TRIM, DECK_TRIM_ONWARDS_CHANGESETS),
                new Contexts(), new LabelExpression());

        // Re-apply only the trim migration (74 cards) so the Ace rows exist.
        // We do this by applying one changeset at a time — apply trim, then block ace removal.
        // Since Liquibase applies in file order, we need to insert the FK reference before update().
        // Insert a hand_card row referencing one of the Ace cards.
        final String aceCardId = "048c87ce-e1e0-519b-be26-05f7b8ae9e5e"; // SPOOFING ACE
        final String handId = "bbbbbbbb-0000-0000-0000-000000000002";
        try (var stmt = connection.createStatement()) {
            stmt.execute("SET REFERENTIAL_INTEGRITY FALSE");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO hand_card (hand_id, card_id) VALUES (?, ?)")) {
            ps.setObject(1, java.util.UUID.fromString(handId));
            ps.setObject(2, java.util.UUID.fromString(aceCardId));
            ps.executeUpdate();
        }
        connection.commit();

        // Act + Assert — applying all migrations must throw because the ace precondition detects the reference.
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
                .isInstanceOf(LiquibaseException.class)
                .hasMessageContaining("Cannot remove Ace cards: they are still referenced by hand_card or trick_play");

        // Assert — the Ace card still exists (migration was refused, not partially applied).
        assertThat(cardExists(connection, aceCardId))
                .as("Ace card must still exist after the precondition HALT")
                .isTrue();
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

    /**
     * Returns the {@code threat_prompt} value for the card with the given UUID.
     *
     * @param id the UUID string to look up
     */
    private static String readThreatPrompt(final Connection conn, final String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT threat_prompt FROM card WHERE id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("card with id %s must exist when reading threat_prompt", id)
                        .isTrue();
                return rs.getString(1);
            }
        }
    }

    /**
     * Counts the changesets that must be rolled back to undo {@code changelogFilename} and everything
     * applied after it.
     *
     * <p>Liquibase counts backwards from the most recent changeset, so a literal count is only correct
     * until someone appends a changelog. Taking every row at or after the target file's own
     * {@code ORDEREXECUTED} makes the depth self-adjusting: a new dated changelog is absorbed rather
     * than shifting what a rollback reaches. This mirrors the helpers in {@code SessionExpiryMigrationTest}
     * and {@code TrickPlaySchemaRoundTripTest}.</p>
     *
     * @param conn an open connection to the migrated database
     * @param changelogFilename the changelog file to roll back to, matched as a path suffix
     * @param ownChangesets the fewest changesets the rollback may legitimately cover
     * @return the number of changesets to pass to {@code Liquibase#rollback}
     * @throws SQLException if {@code DATABASECHANGELOG} cannot be queried
     */
    private static int changesetsAppliedFrom(final Connection conn, final String changelogFilename,
            final int ownChangesets) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM DATABASECHANGELOG "
                        + "WHERE ORDEREXECUTED >= ("
                        + "  SELECT MIN(ORDEREXECUTED) FROM DATABASECHANGELOG "
                        + "  WHERE FILENAME LIKE ?)")) {
            ps.setString(1, "%" + changelogFilename);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("DATABASECHANGELOG must be queryable after update() — "
                                + "a missing row set means Liquibase never ran")
                        .isTrue();
                final int count = rs.getInt(1);
                assertThat(count)
                        .as("rolling back to %s requires at least its own %d changesets; "
                                + "a smaller count means DATABASECHANGELOG was not populated as expected",
                                changelogFilename, ownChangesets)
                        .isGreaterThanOrEqualTo(ownChangesets);
                return count;
            }
        }
    }
}
