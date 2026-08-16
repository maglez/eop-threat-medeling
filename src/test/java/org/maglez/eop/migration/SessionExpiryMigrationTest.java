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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the 006-session-expiry.xml migration round-trips correctly:
 * forward migration adds the {@code expires_at} column and its index to
 * {@code game_session}; rollback removes them; and re-applying the migration
 * restores them.
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
 * <p>{@code CHANGESET_COUNT_006} is guarded against the actual XML: the classpath
 * resource is read and the number of {@code <changeSet} occurrences is counted and
 * asserted to equal the constant, so a new changeset added to the file without
 * updating the constant fails loudly rather than silently rolling back too few
 * changesets.
 *
 * <p>The file contains 2 changesets: one for PostgreSQL (MARK_RAN on H2 because
 * the {@code <dbms type="postgresql"/>} precondition does not match) and one for H2
 * (which runs). Both are counted because the constant guards the file's total size,
 * not the number that execute.
 */
@DisplayName("006-session-expiry migration round-trip")
class SessionExpiryMigrationTest {

    /** Unique in-memory database name — does not collide with the suite's eop-test. */
    private static final String JDBC_URL =
            "jdbc:h2:mem:session-expiry-roundtrip;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /** The table that 006 modifies. */
    private static final String TABLE_GAME_SESSION = "GAME_SESSION";

    /** The column that 006 adds to game_session. */
    private static final String COLUMN_EXPIRES_AT = "EXPIRES_AT";

    /** The index that 006 creates on game_session.expires_at. */
    private static final String INDEX_EXPIRES_AT = "IDX_GAME_SESSION_EXPIRES_AT";

    /**
     * Number of changesets in 006-session-expiry.xml.
     *
     * <p>This constant is guarded by {@link #changesetCountMatchesXml()}: the classpath
     * resource is read and the number of {@code <changeSet} occurrences is counted and
     * asserted to equal this value.
     *
     * <p>The file contains 2 changesets: one for PostgreSQL (MARK_RAN on H2) and one
     * for H2 (which runs). Both are counted because the constant guards the file's total
     * size, not the number that execute.
     */
    private static final int CHANGESET_COUNT_006 = 2;

    /**
     * Path to the changeset file on the classpath.
     */
    private static final String CHANGESET_CLASSPATH =
            "db/changelog/changes/006-session-expiry.xml";

    /**
     * Bare file name of 006, used to locate its rows in {@code DATABASECHANGELOG}.
     * Liquibase records the changelog-relative path, so this is matched with a trailing
     * {@code LIKE '%...'} rather than compared for equality.
     */
    private static final String CHANGESET_FILENAME_006 = "006-session-expiry.xml";

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
    @DisplayName("forward migration adds expires_at column and its index to game_session")
    void forwardMigrationAddsExpiresAtColumn() throws Exception {
        // Arrange — database is empty; Liquibase has not run yet.

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase leaves the connection with auto-commit disabled after running migrations.
        // Re-enable it so that subsequent metadata queries see the committed schema.
        connection.setAutoCommit(true);

        // Assert — expires_at column exists on game_session
        assertThat(columnExists(connection, TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("game_session.expires_at must exist after forward migration")
                .isTrue();

        // Assert — index on expires_at exists
        assertThat(indexExists(connection, TABLE_GAME_SESSION, INDEX_EXPIRES_AT))
                .as("idx_game_session_expires_at must exist after forward migration")
                .isTrue();
    }

    @Test
    @DisplayName("rollback of every changeset from 006 onward removes expires_at and its index")
    void rollbackRemovesExpiresAtColumn() throws Exception {
        // Arrange — apply the full migration first
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Act — roll back every changeset applied at or after 006's first
        liquibase.rollback(changesetsAppliedFrom006(connection), new Contexts(), new LabelExpression());

        // Assert — expires_at column is gone
        assertThat(columnExists(connection, TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("game_session.expires_at must be absent after rolling back 006")
                .isFalse();

        // Assert — index is gone
        assertThat(indexExists(connection, TABLE_GAME_SESSION, INDEX_EXPIRES_AT))
                .as("idx_game_session_expires_at must be absent after rolling back 006")
                .isFalse();
    }

    @Test
    @DisplayName("re-applying 006 after rollback restores expires_at and its index")
    void reapplyAfterRollbackRestoresExpiresAt() throws Exception {
        // Arrange — apply, roll back, then apply again
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
        liquibase.rollback(changesetsAppliedFrom006(connection), new Contexts(), new LabelExpression());

        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert — expires_at column is back
        assertThat(columnExists(connection, TABLE_GAME_SESSION, COLUMN_EXPIRES_AT))
                .as("game_session.expires_at must be restored after re-applying 006")
                .isTrue();

        // Assert — index is back
        assertThat(indexExists(connection, TABLE_GAME_SESSION, INDEX_EXPIRES_AT))
                .as("idx_game_session_expires_at must be restored after re-applying 006")
                .isTrue();
    }

    /**
     * Guards {@link #CHANGESET_COUNT_006} against the actual XML file.
     *
     * <p>Counts {@code <changeSet} occurrences in the classpath resource and asserts
     * equality with the constant. If a new changeset is added to the file without
     * updating the constant, this test fails loudly rather than silently rolling back
     * too few changesets.
     */
    @Test
    @DisplayName("CHANGESET_COUNT_006 matches the actual number of <changeSet> elements in 006-session-expiry.xml")
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
                .as("CHANGESET_COUNT_006 (%d) must equal the number of <changeSet> elements in %s (%d). "
                        + "If a changeset was added or removed, update CHANGESET_COUNT_006 to match.",
                        CHANGESET_COUNT_006, CHANGESET_CLASSPATH, count)
                .isEqualTo(CHANGESET_COUNT_006);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Number of changesets that must be rolled back to undo all of {@code 006} — which is
     * every changeset applied at or after 006's first, not merely 006's own two.
     *
     * <p>Liquibase's count-based rollback undoes the <em>last N applied</em> changesets, so
     * rolling back {@link #CHANGESET_COUNT_006} reaches all of 006 only while 006 is the final
     * changelog file. Reading from {@code DATABASECHANGELOG} ensures any future changelog file
     * added after 006 is counted automatically.
     */
    private static int changesetsAppliedFrom006(final Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM DATABASECHANGELOG "
                        + "WHERE ORDEREXECUTED >= ("
                        + "  SELECT MIN(ORDEREXECUTED) FROM DATABASECHANGELOG "
                        + "  WHERE FILENAME LIKE ?)")) {
            ps.setString(1, "%" + CHANGESET_FILENAME_006);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("DATABASECHANGELOG must be queryable after update() — "
                                + "a missing row set means Liquibase never ran")
                        .isTrue();
                final int count = rs.getInt(1);
                assertThat(count)
                        .as("rolling back all of %s requires at least its own %d changesets; "
                                + "a smaller count means DATABASECHANGELOG was not populated as expected",
                                CHANGESET_FILENAME_006, CHANGESET_COUNT_006)
                        .isGreaterThanOrEqualTo(CHANGESET_COUNT_006);
                return count;
            }
        }
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
     * Returns {@code true} if the given index exists on the given table.
     * Both names are compared case-insensitively via upper-casing.
     *
     * <p>Uses {@code DatabaseMetaData.getIndexInfo} with {@code unique=false} to include
     * non-unique indexes (the expires_at index is non-unique).
     */
    private static boolean indexExists(
            final Connection conn,
            final String tableName,
            final String indexName) throws SQLException {
        final DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getIndexInfo(null, null, tableName.toUpperCase(), false, false)) {
            while (rs.next()) {
                final String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
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
        final ClassLoader loader = SessionExpiryMigrationTest.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            assertThat(stream)
                    .as("classpath resource '%s' must exist — is the test running from the project root?", path)
                    .isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
