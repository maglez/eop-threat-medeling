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

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the 004-trick-play-schema.xml migration round-trips correctly:
 * forward migration creates all expected tables, columns, unique constraints and
 * foreign keys; rollback removes them; and re-applying the migration restores them
 * including all constraints.
 *
 * <p>No Spring context. This is a Liquibase-API test that needs full isolation
 * over its own database, not context verification. Using {@code @SpringBootTest}
 * here would share the suite's {@code eop-test} datasource, which Spring's
 * Liquibase has already migrated — so {@code update()} would find nothing pending
 * and the rollback would target the wrong changeset.
 *
 * <p>The database name is unique per test class to avoid collisions with the
 * suite's {@code eop-test} database or with parallel runs.
 *
 * <p>Constraint metadata is asserted after re-apply because changeset 007 adds
 * {@code fk_trick_winner_play} in a separate changeset from the column it
 * references (changeset 004), so ordering is load-bearing: a rollback-and-reapply
 * that silently lost a constraint would pass the table/column existence checks
 * while leaving the schema weaker than the one that ships.
 *
 * <p>{@code CHANGESET_COUNT_004} is guarded against the actual XML: the classpath
 * resource is read and the number of {@code <changeSet} occurrences is counted and
 * asserted to equal the constant, so a new changeset added to the file without
 * updating the constant fails loudly rather than silently rolling back too few
 * changesets.
 *
 * <p>The file now contains 9 changesets (001–009). Changesets 008 and 009 were
 * appended to close the seat-impersonation defect at the storage layer:
 * changeset 008 adds {@code uq_player_id_seat} on {@code player(id, seat_order)}
 * (a prerequisite for the composite foreign keys), and changeset 009 adds
 * {@code fk_hand_player_seat} and {@code fk_trick_play_player_seat}, both
 * referencing {@code player(id, seat_order)} with {@code ON DELETE CASCADE}.
 * The two single-column player foreign keys ({@code fk_hand_player} and
 * {@code fk_trick_play_player}) were removed because the composite keys subsume
 * them: both columns are NOT NULL, so MATCH SIMPLE has no partial-null escape.
 * The total foreign-key count remains 10.
 */
@DisplayName("004-trick-play-schema migration round-trip")
class TrickPlaySchemaRoundTripTest {

    /** Unique in-memory database name — does not collide with the suite's eop-test. */
    private static final String JDBC_URL =
            "jdbc:h2:mem:trick-play-schema-roundtrip;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /** The five new tables introduced by 004. */
    private static final Set<String> TABLES_004 = Set.of(
            "HAND", "HAND_CARD", "TRICK", "TRICK_PLAY", "TRICK_PLAY_COMPONENT");

    /** The column added to game_session by changeset 001 of 004. */
    private static final String COLUMN_CURRENT_LEADER_SEAT = "CURRENT_LEADER_SEAT";
    private static final String TABLE_GAME_SESSION = "GAME_SESSION";

    /**
     * Number of changesets in 004-trick-play-schema.xml.
     *
     * <p>This constant is guarded by {@link #changesetCountMatchesXml()}: the classpath
     * resource is read and the number of {@code <changeSet} occurrences is counted and
     * asserted to equal this value. If a new changeset is added to the file without
     * updating this constant, that test fails loudly rather than silently rolling back
     * too few changesets and leaving the schema in a partially-rolled-back state.
     *
     * <p>The file now contains 9 changesets: 001–007 (original) plus 008
     * ({@code uq_player_id_seat} on {@code player}) and 009
     * ({@code fk_hand_player_seat} and {@code fk_trick_play_player_seat}).
     */
    private static final int CHANGESET_COUNT_004 = 9;

    /**
     * Path to the changeset file on the classpath.
     * The master changelog uses {@code relativeToChangelogFile="true"}, so the file
     * is at {@code db/changelog/changes/004-trick-play-schema.xml} on the classpath.
     */
    private static final String CHANGESET_CLASSPATH =
            "db/changelog/changes/004-trick-play-schema.xml";

    /**
     * The six unique constraints that 004 declares.
     * Asserted after re-apply to catch a rollback-and-reapply that silently loses one.
     *
     * <p>Five constraints are on the tables 004 creates ({@code hand}, {@code trick},
     * {@code trick_play}). The sixth, {@code uq_player_id_seat}, is on {@code player}
     * (created by 003-session-lifecycle.xml) and is added by changeset 008 of 004 as a
     * prerequisite for the composite foreign keys in changeset 009.
     */
    private static final Set<String> UNIQUE_CONSTRAINTS_004 = Set.of(
            "UQ_HAND_SESSION_SEAT",
            "UQ_TRICK_SESSION_SEQUENCE",
            "UQ_TRICK_PLAY_TRICK_SEAT",
            "UQ_TRICK_PLAY_TRICK_CARD",
            "UQ_TRICK_PLAY_TRICK_PLAYER",
            "UQ_PLAYER_ID_SEAT");

    /**
     * The ten foreign keys that 004 declares (changesets 002–007, 009).
     * Asserted after re-apply to catch a rollback-and-reapply that silently loses one.
     *
     * <p>Changeset 007 adds {@code fk_trick_winner_play} in a separate changeset from the
     * column it references (changeset 004), so ordering is load-bearing.
     *
     * <p>Changeset 009 adds {@code fk_hand_player_seat} and
     * {@code fk_trick_play_player_seat} (composite foreign keys referencing
     * {@code player(id, seat_order)}), replacing the former single-column keys
     * {@code fk_hand_player} and {@code fk_trick_play_player}. The total count
     * remains 10.
     */
    private static final Set<String> FOREIGN_KEYS_004 = Set.of(
            "FK_HAND_GAME_SESSION",
            "FK_HAND_PLAYER_SEAT",
            "FK_HAND_CARD_HAND",
            "FK_HAND_CARD_CARD",
            "FK_TRICK_GAME_SESSION",
            "FK_TRICK_PLAY_TRICK",
            "FK_TRICK_PLAY_CARD",
            "FK_TRICK_PLAY_PLAYER_SEAT",
            "FK_TRICK_PLAY_COMPONENT_TRICK_PLAY",
            "FK_TRICK_WINNER_PLAY");

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
    @DisplayName("forward migration creates all five new tables and the current_leader_seat column")
    void forwardMigrationCreatesExpectedSchema() throws Exception {
        // Arrange — database is empty; Liquibase has not run yet.

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase leaves the connection with auto-commit disabled after running migrations.
        // Re-enable it so that subsequent metadata queries see the committed schema.
        connection.setAutoCommit(true);

        // Assert — all five tables exist
        final Set<String> tables = tableNames(connection);
        assertThat(tables)
                .as("all five tables introduced by 004 must exist after update()")
                .containsAll(TABLES_004);

        // Assert — game_session.current_leader_seat exists
        assertThat(columnExists(connection, TABLE_GAME_SESSION, COLUMN_CURRENT_LEADER_SEAT))
                .as("game_session.current_leader_seat must exist after update()")
                .isTrue();
    }

    @Test
    @DisplayName("rollback of all 9 changesets in 004 removes the five tables and the column")
    void rollbackRemovesSchemaAddedBy004() throws Exception {
        // Arrange — apply the full migration first
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Act — roll back exactly the 9 changesets that 004 added
        liquibase.rollback(CHANGESET_COUNT_004, new Contexts(), new LabelExpression());

        // Assert — all five tables are gone
        final Set<String> tables = tableNames(connection);
        assertThat(tables)
                .as("tables introduced by 004 must be absent after rolling back all 9 changesets")
                .doesNotContainAnyElementsOf(TABLES_004);

        // Assert — game_session.current_leader_seat is gone
        assertThat(columnExists(connection, TABLE_GAME_SESSION, COLUMN_CURRENT_LEADER_SEAT))
                .as("game_session.current_leader_seat must be absent after rollback")
                .isFalse();

        // Assert — changeset 008 rollback: uq_player_id_seat is gone but player table still exists.
        //
        // Changeset 008 adds uq_player_id_seat to the player table (which is owned by
        // 003-session-lifecycle.xml, not by 004). Its rollback must drop only the constraint,
        // not the table. This pair is the only new rollback-direction coverage that changeset 008
        // earns: it would catch a rollback that accidentally dropped the player table, or one
        // that left the constraint behind (making a subsequent re-apply fail with a duplicate
        // constraint name).
        final Set<String> uniqueConstraints = uniqueConstraintNames(connection);
        assertThat(uniqueConstraints)
                .as("uq_player_id_seat must be absent after rolling back changeset 008 "
                        + "(the rollback drops only the constraint, not the player table)")
                .doesNotContain("UQ_PLAYER_ID_SEAT");
        assertThat(tables)
                .as("player table must still exist after rolling back 004 "
                        + "(it is owned by 003-session-lifecycle.xml, not by 004)")
                .contains("PLAYER");
    }

    @Test
    @DisplayName("re-applying 004 after rollback restores all five tables and the column")
    void reapplyAfterRollbackRestoresSchema() throws Exception {
        // Arrange — apply, roll back, then apply again
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(CHANGESET_COUNT_004, new Contexts(), new LabelExpression());

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — all five tables are back
        final Set<String> tables = tableNames(connection);
        assertThat(tables)
                .as("all five tables must be restored after re-applying 004")
                .containsAll(TABLES_004);

        // Assert — game_session.current_leader_seat is back
        assertThat(columnExists(connection, TABLE_GAME_SESSION, COLUMN_CURRENT_LEADER_SEAT))
                .as("game_session.current_leader_seat must be restored after re-applying 004")
                .isTrue();
    }

    /**
     * After rollback and re-apply, all six unique constraints and all ten foreign keys
     * declared by 004 must be present.
     *
     * <p>This test is the reason constraint metadata is asserted at all: changeset 007
     * adds {@code fk_trick_winner_play} in a separate changeset from the column it
     * references (changeset 004), so ordering is load-bearing. A rollback-and-reapply
     * that silently lost a constraint would pass the table/column existence checks while
     * leaving the schema weaker than the one that ships.
     *
     * <p>Changeset 009 adds {@code fk_hand_player_seat} and
     * {@code fk_trick_play_player_seat}; changeset 008 adds {@code uq_player_id_seat}
     * on {@code player}. All six unique constraints and all ten foreign keys must be
     * present after re-apply.
     */
    @Test
    @DisplayName("re-applying 004 after rollback restores all six unique constraints and all ten foreign keys")
    void reapplyAfterRollbackRestoresConstraints() throws Exception {
        // Arrange — apply, roll back, then apply again
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(CHANGESET_COUNT_004, new Contexts(), new LabelExpression());

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — all six unique constraints are present
        final Set<String> uniqueConstraints = uniqueConstraintNames(connection);
        assertThat(uniqueConstraints)
                .as("all six unique constraints introduced by 004 must be present after re-apply. "
                        + "Missing constraints indicate a rollback-and-reapply ordering defect.")
                .containsAll(UNIQUE_CONSTRAINTS_004);

        // Assert — all ten foreign keys are present
        final Set<String> foreignKeys = foreignKeyNames(connection);
        assertThat(foreignKeys)
                .as("all ten foreign keys introduced by 004 must be present after re-apply. "
                        + "Missing fk_trick_winner_play in particular would indicate changeset 007 "
                        + "was not re-applied (ordering is load-bearing: the column is in changeset 004, "
                        + "the key is in changeset 007).")
                .containsAll(FOREIGN_KEYS_004);
    }

    /**
     * Guards {@link #CHANGESET_COUNT_004} against the actual XML file.
     *
     * <p>Counts {@code <changeSet} occurrences in the classpath resource and asserts
     * equality with the constant. If a new changeset is added to the file without
     * updating the constant, this test fails loudly rather than silently rolling back
     * too few changesets.
     */
    @Test
    @DisplayName("CHANGESET_COUNT_004 matches the actual number of <changeSet> elements in 004-trick-play-schema.xml")
    void changesetCountMatchesXml() throws IOException {
        // Arrange — read the changeset XML as a raw string from the classpath
        final String changesetXml = readClasspathResource(CHANGESET_CLASSPATH);

        // Act — count <changeSet occurrences (the opening tag, not the closing tag)
        final Pattern changeSetPattern = Pattern.compile("<changeSet\\b");
        final Matcher matcher = changeSetPattern.matcher(changesetXml);
        int count = 0;
        while (matcher.find()) {
            count++;
        }

        // Assert — the count must equal the constant
        assertThat(count)
                .as("CHANGESET_COUNT_004 (%d) must equal the number of <changeSet> elements in %s (%d). "
                        + "If a changeset was added or removed, update CHANGESET_COUNT_004 to match, "
                        + "or the rollback tests will roll back the wrong number of changesets.",
                        CHANGESET_COUNT_004, CHANGESET_CLASSPATH, count)
                .isEqualTo(CHANGESET_COUNT_004);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the upper-cased names of all user tables visible in the connection's schema.
     * H2 stores identifiers in upper case by default.
     */
    private static Set<String> tableNames(final Connection conn) throws SQLException {
        final DatabaseMetaData meta = conn.getMetaData();
        final Set<String> names = new HashSet<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME").toUpperCase());
            }
        }
        return names;
    }

    /**
     * Returns {@code true} if the given column exists in the given table.
     * Both names are compared case-insensitively via upper-casing.
     */
    private static boolean columnExists(
            final Connection conn,
            final String tableName,
            final String columnName) throws SQLException {
        final DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return rs.next();
        }
    }

    /**
     * Returns the upper-cased names of all UNIQUE constraints across the tables
     * introduced or modified by 004, queried from {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS}.
     *
     * <p>H2's {@code DatabaseMetaData.getIndexInfo} appends a generated suffix to
     * unique constraint index names (e.g. {@code UQ_HAND_SESSION_SEAT_INDEX_2}),
     * which does not match the declared constraint name. Using
     * {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} returns the exact names as
     * declared in the DDL.
     *
     * <p>The {@code PLAYER} table is included because changeset 008 adds
     * {@code uq_player_id_seat} to it. The table is owned by 003-session-lifecycle.xml
     * but the constraint is owned by 004, so it must appear in this set.
     */
    private static Set<String> uniqueConstraintNames(final Connection conn) throws SQLException {
        final Set<String> names = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_TYPE = 'UNIQUE' "
                        + "AND TABLE_NAME IN ('HAND', 'TRICK', 'TRICK_PLAY', 'PLAYER')")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("CONSTRAINT_NAME").toUpperCase());
                }
            }
        }
        return names;
    }

    /**
     * Returns the upper-cased FK constraint names for the tables introduced by 004,
     * queried from {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS}.
     *
     * <p>Using {@code INFORMATION_SCHEMA} rather than {@code DatabaseMetaData.getImportedKeys}
     * is consistent with {@link #uniqueConstraintNames} and avoids any name-mangling
     * that JDBC metadata layers may apply.
     */
    private static Set<String> foreignKeyNames(final Connection conn) throws SQLException {
        final Set<String> names = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_TYPE = 'FOREIGN KEY' "
                        + "AND TABLE_NAME IN ('HAND', 'HAND_CARD', 'TRICK', 'TRICK_PLAY', 'TRICK_PLAY_COMPONENT')")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("CONSTRAINT_NAME").toUpperCase());
                }
            }
        }
        return names;
    }

    /**
     * Reads a classpath resource as a UTF-8 string.
     *
     * @param path the classpath-relative path
     * @return the full content of the resource
     * @throws IOException    if the resource cannot be read
     * @throws AssertionError if the resource is not found on the classpath
     */
    private static String readClasspathResource(final String path) throws IOException {
        final ClassLoader loader = TrickPlaySchemaRoundTripTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertThat(stream)
                    .as("classpath resource '%s' must exist — is the test running from the project root?", path)
                    .isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
