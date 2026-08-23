package org.maglez.eop.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the entire Liquibase changelog to a real PostgreSQL 17 container.
 *
 * <p>Until EOP-164 the changelog was only ever executed against H2. That mattered most for
 * {@code 006-session-expiry.xml}, which carries two mutually exclusive changesets selected by a
 * {@code <dbms>} precondition: the H2 one ran in CI, and the PostgreSQL one ran for the first time
 * in production, verified by nothing. This test is the mirror image of
 * {@link SessionExpiryMigrationTest} -- same changelog, the other branch.
 *
 * <p>Named {@code *IT} rather than {@code *Test} on purpose. Surefire's default includes do not
 * match it, so {@code ./mvnw test} stays H2-only and sub-second; failsafe picks it up at the
 * {@code integration-test} phase during {@code ./mvnw verify}. Renaming it to {@code *Test} would
 * silently move it into the fast suite and point it at H2, which is the one engine it is not
 * supposed to be testing.
 *
 * <p>Follows the raw-Liquibase idiom of the existing migration tests rather than using
 * {@code @SpringBootTest}: a Spring context would migrate the datasource before the test body runs,
 * leaving nothing pending for {@code update()} and making a rollback depth meaningless.
 */
@DisplayName("Liquibase changelog against PostgreSQL 17")
class PostgresChangelogIT {

    /**
     * Own database inside the shared container, so execution order against other integration
     * tests cannot matter.
     */
    private static final String DATABASE_NAME = "eop_changelog_it";

    private static final String CHANGELOG_MASTER = "db/changelog/db.changelog-master.xml";

    private static final String CHANGES_DIRECTORY = "db/changelog/changes/";

    /**
     * Every changelog file, with the number of changesets each declares. Asserted against the files
     * themselves by {@link #changesetTotalMatchesTheChangelogFiles()}, so appending a changeset
     * without updating this map fails loudly instead of weakening the total below.
     */
    private static final Map<String, Integer> CHANGESETS_PER_FILE = changesetsPerFile();

    /**
     * The number of {@code DATABASECHANGELOG} rows the full changelog must produce.
     *
     * <p>26, not 25. Only 25 changesets <em>execute</em> on any one engine, because
     * {@code 006-session-expiry.xml}'s two branches are mutually exclusive -- but a changeset
     * skipped by an {@code onFail="MARK_RAN"} precondition still records a row, so the row count is
     * the same on both engines and it is the total in the files that this must equal.
     */
    private static final int EXPECTED_CHANGESET_ROWS = 26;

    private static final String CHANGESET_POSTGRES_BRANCH = "001-add-expires-at-postgresql";

    private static final String CHANGESET_H2_BRANCH = "001-add-expires-at-h2";

    private static final String EXECTYPE_EXECUTED = "EXECUTED";

    private static final String EXECTYPE_MARK_RAN = "MARK_RAN";

    private Connection connection;

    private Liquibase liquibase;

    @BeforeEach
    void setUp() throws SQLException, LiquibaseException {
        connection = PostgresTestContainer.freshDatabase(DATABASE_NAME);
        final Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase(CHANGELOG_MASTER, new ClassLoaderResourceAccessor(), database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (liquibase != null) {
            liquibase.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("resolves the PostgreSQL dialect rather than falling back to a generic database")
    void resolvesThePostgresDialect() throws Exception {
        // Act
        final Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));

        // Assert -- the <dbms type="postgresql"> preconditions in the changelog are matched against
        // this short name, so a wrong dialect would silently skip every PostgreSQL-gated changeset
        // and the suite would pass while testing nothing.
        assertThat(database.getShortName())
                .as("Liquibase database short name, which <dbms type=\"...\"> is matched against")
                .isEqualTo("postgresql");
        assertThat(connection.getMetaData().getDatabaseMajorVersion())
                .as("PostgreSQL major version -- production runs 17 (compose.app.yml)")
                .isEqualTo(17);
    }

    @Test
    @DisplayName("applies every changeset in the changelog")
    void appliesEveryChangeset() throws Exception {
        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase leaves auto-commit disabled. PostgreSQL DDL is transactional, so without this
        // the schema it just created is not visible to the queries below.
        connection.setAutoCommit(true);

        // Assert
        assertThat(changelogRowCount())
                .as("DATABASECHANGELOG rows after applying %s", CHANGELOG_MASTER)
                .isEqualTo(EXPECTED_CHANGESET_ROWS);
        assertThat(failedOrSkippedExecTypes())
                .as("changesets whose EXECTYPE is neither EXECUTED nor MARK_RAN")
                .isEmpty();
    }

    @Test
    @DisplayName("runs the PostgreSQL branch of 006 and marks the H2 branch as run")
    void selectsThePostgresBranchOfSessionExpiry() throws Exception {
        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert -- this is the assertion the H2 suite structurally cannot make. On H2 these two
        // exec types are the other way round.
        assertThat(execTypeOf(CHANGESET_POSTGRES_BRANCH))
                .as("EXECTYPE of the <dbms type=\"postgresql\"> branch of 006-session-expiry.xml")
                .isEqualTo(EXECTYPE_EXECUTED);
        assertThat(execTypeOf(CHANGESET_H2_BRANCH))
                .as("EXECTYPE of the <dbms type=\"h2\"> branch of 006-session-expiry.xml")
                .isEqualTo(EXECTYPE_MARK_RAN);
    }

    @Test
    @DisplayName("renders expires_at with the PostgreSQL interval syntax accepted by the engine")
    void rendersThePostgresExpiresAtDefault() throws Exception {
        // Act
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);

        // Assert -- NOW() + INTERVAL '24 hours' is the PostgreSQL-only spelling. H2 rejects it, and
        // PostgreSQL rejects H2's INTERVAL '24' HOUR, which is why 006 has two changesets at all.
        // PostgreSQL normalises the stored expression, so match on its parts rather than the source
        // text.
        final String columnDefault = columnDefaultOf("game_session", "expires_at");
        assertThat(columnDefault)
                .as("column_default of game_session.expires_at as PostgreSQL stored it")
                .isNotNull()
                .containsIgnoringCase("now()")
                .containsIgnoringCase("interval");
        assertThat(columnTypeOf("game_session", "expires_at"))
                .as("declared type of game_session.expires_at")
                .isEqualTo("timestamp with time zone");
        assertThat(isNullable("game_session", "expires_at"))
                .as("game_session.expires_at is declared NOT NULL")
                .isFalse();
    }

    @Test
    @DisplayName("expected changeset total matches the changelog files on disk")
    void changesetTotalMatchesTheChangelogFiles() {
        // Act
        int counted = 0;
        final Map<String, Integer> mismatches = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> entry : CHANGESETS_PER_FILE.entrySet()) {
            final int actual = countChangesets(CHANGES_DIRECTORY + entry.getKey());
            counted += actual;
            if (actual != entry.getValue()) {
                mismatches.put(entry.getKey(), actual);
            }
        }

        // Assert -- guards EXPECTED_CHANGESET_ROWS, which is otherwise a literal that silently goes
        // stale the moment a changelog is appended.
        assertThat(mismatches)
                .as("changelog files whose <changeSet> count differs from CHANGESETS_PER_FILE")
                .isEmpty();
        assertThat(counted)
                .as("total <changeSet> elements across %d changelog files", CHANGESETS_PER_FILE.size())
                .isEqualTo(EXPECTED_CHANGESET_ROWS);
    }

    private static Map<String, Integer> changesetsPerFile() {
        final Map<String, Integer> perFile = new LinkedHashMap<>();
        perFile.put("001-card-catalogue.xml", 2);
        perFile.put("002-real-deck.xml", 2);
        perFile.put("003-session-lifecycle.xml", 2);
        perFile.put("004-trick-play-schema.xml", 9);
        perFile.put("005-seat-and-sequence-bounds.xml", 3);
        perFile.put("006-session-expiry.xml", 2);
        perFile.put("2026-08-16--game-result.xml", 2);
        perFile.put("2026-08-17--trim-deck-to-74-printed-cards.xml", 1);
        perFile.put("2026-08-18--remove-ace-cards.xml", 1);
        perFile.put("2026-08-22--widen-join-code-to-8-characters.xml", 2);
        return perFile;
    }

    /**
     * Counts {@code <changeSet} elements in a changelog on the test classpath.
     *
     * @param classpathResource changelog path relative to the classpath root
     * @return the number of {@code <changeSet} elements the file declares
     */
    private static int countChangesets(final String classpathResource) {
        final String xml = readClasspathResource(classpathResource);
        final Matcher matcher = Pattern.compile("<changeSet\\b").matcher(xml);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String readClasspathResource(final String classpathResource) {
        try (InputStream stream = PostgresChangelogIT.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertThat(stream).as("classpath resource %s", classpathResource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new AssertionError("Could not read " + classpathResource, e);
        }
    }

    private int changelogRowCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM databasechangelog")) {
            assertThat(rows.next()).as("DATABASECHANGELOG count query returned a row").isTrue();
            return rows.getInt(1);
        }
    }

    private String execTypeOf(final String changesetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT exectype FROM databasechangelog WHERE id = ?")) {
            statement.setString(1, changesetId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("DATABASECHANGELOG row for changeset %s", changesetId).isTrue();
                return rows.getString(1);
            }
        }
    }

    private Map<String, String> failedOrSkippedExecTypes() throws SQLException {
        final Map<String, String> unexpected = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, exectype FROM databasechangelog ORDER BY orderexecuted")) {
            while (rows.next()) {
                final String execType = rows.getString(2);
                if (!EXECTYPE_EXECUTED.equals(execType) && !EXECTYPE_MARK_RAN.equals(execType)) {
                    unexpected.put(rows.getString(1), execType);
                }
            }
        }
        return unexpected;
    }

    /**
     * Reads a column's default expression as PostgreSQL stored it.
     *
     * <p>Identifiers are passed lower-cased: PostgreSQL folds unquoted identifiers to lower case,
     * the opposite of the {@code toUpperCase()} the H2 migration tests use.
     *
     * @param table  table name
     * @param column column name
     * @return the stored default expression, or {@code null} if the column has none
     * @throws SQLException if the catalogue query fails
     */
    private String columnDefaultOf(final String table, final String column) throws SQLException {
        return columnAttribute("column_default", table, column);
    }

    private String columnTypeOf(final String table, final String column) throws SQLException {
        return columnAttribute("data_type", table, column);
    }

    private boolean isNullable(final String table, final String column) throws SQLException {
        return "YES".equals(columnAttribute("is_nullable", table, column));
    }

    private String columnAttribute(final String attribute, final String table, final String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + attribute + " FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table.toLowerCase());
            statement.setString(2, column.toLowerCase());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("information_schema row for %s.%s", table, column).isTrue();
                return rows.getString(1);
            }
        }
    }
}
