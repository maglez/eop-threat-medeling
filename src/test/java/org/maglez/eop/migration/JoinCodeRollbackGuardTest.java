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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves on H2 that the rollback guard added by
 * {@code 2026-08-23--guard-join-code-rollback.xml} (EOP-163) refuses to unwind the join-code
 * widening when doing so would destroy live join codes, and still unwinds it losslessly when it
 * would not.
 *
 * <p><strong>What was wrong, and why it needed fixing on two engines.</strong>
 * {@code 2026-08-22--widen-join-code-to-8-characters.xml} widened
 * {@code game_session.join_code} from {@code VARCHAR(6)} to {@code VARCHAR(8)} (changeset 001) and
 * padded pre-existing six-character codes with {@code '00'} (changeset 002). Liquibase unwinds in
 * reverse execution order, so its rollback runs 002 first and 001 second:
 *
 * <ol>
 *   <li>002's rollback does {@code UPDATE game_session SET join_code = SUBSTRING(join_code, 1, 6)
 *       WHERE LENGTH(join_code) = 8 AND join_code LIKE '%00'} — which truncates a
 *       <em>genuinely generated</em> code ending {@code 00} just as readily as a padded one, on
 *       <strong>both</strong> engines;</li>
 *   <li>001's rollback narrows the column back to {@code VARCHAR(6)}.</li>
 * </ol>
 *
 * <p><strong>The two engines then failed differently, which is the reason this class exists
 * alongside {@code PostgresRollbackRoundTripIT} rather than instead of it.</strong> On PostgreSQL
 * step 2 narrows with an explicit cast, and an explicit cast to {@code varchar(n)} <em>truncates</em>
 * rather than raising an error — so every live eight-character code was silently rewritten to its
 * first six characters and Liquibase reported the rollback as a success. On H2 step 2 instead
 * hard-fails with {@code Value too long for column}, which is louder but is still an aborted
 * rollback mid-flight, and H2 was never safe either because step 1 had already silently truncated
 * any genuine code ending {@code 00}. Silent loss reported as success, and a hard failure partway
 * through, are two different defects with one shared cause; the guard replaces both with a single
 * deterministic refusal that behaves identically on both engines.
 *
 * <p><strong>Why refusal is the fix rather than a sharper predicate.</strong>
 * After the forward migration a padded code ({@code QRSTUV} → {@code QRSTUV00}) is byte-for-byte
 * indistinguishable from a genuinely generated code that happens to end {@code 00}: the padding
 * destroyed the distinction and nothing in the schema records which rows were touched. No predicate
 * can separate them, so no rollback can be lossless on populated data. Refusing before anything is
 * modified is the only honest outcome, and recovery is a forward migration.
 *
 * <p>The guard is a validated {@code CHECK (LENGTH(join_code) &lt;= 6)} constraint that is added and
 * immediately dropped. Adding a validated CHECK fails if and only if a violating row exists, so a
 * wide code aborts the rollback with nothing modified, and no wide code leaves the schema exactly as
 * it was.
 *
 * <p><strong>Traps this class had to avoid.</strong>
 * <strong>No Spring context.</strong> This drives the Liquibase API directly and owns its own
 * database. {@code @SpringBootTest} would share the suite's already-migrated datasource, so
 * {@code update()} would find nothing pending and the rollback would target the wrong changeset.
 * The database name is unique to this class so it cannot collide with the suite's {@code eop-test}
 * database or with a parallel run.
 *
 * <p><strong>{@code update()} must run before the depth is computed.</strong> The depth is read out
 * of {@code DATABASECHANGELOG}, which does not exist until Liquibase has run.
 *
 * <p><strong>The rollback depth is computed, never a literal.</strong> {@code <includeAll>} orders
 * {@code changes/} alphabetically, so any later dated changelog is applied after these two and
 * shifts what a count-based rollback reaches. A literal {@code rollback(1)} silently unwound the
 * <em>wrong</em> changeset the first time a migration was appended — recorded in
 * {@code DeckTrimMigrationRoundTripTest} — and this very story is another instance of the same
 * hazard, because the guard changelog makes fully unwinding the widening need three changesets
 * rather than two.
 *
 * <p><strong>Identifier case folding runs in opposite directions on the two engines.</strong> H2
 * folds unquoted identifiers to UPPER case where PostgreSQL folds them to lower, so
 * {@code information_schema} is queried here with upper-case names, and the constraint name is
 * matched case-insensitively: H2 reports
 * {@code CK_EOP163_JOIN_CODE_FITS_VARCHAR6} where PostgreSQL reports the same name in lower case.
 *
 * <p><strong>Liquibase leaves auto-commit disabled.</strong> Every call goes through
 * {@link #update()} / {@link #rollbackPastTheWidening()}, which re-enable it so the subsequent
 * metadata queries see committed state. A <em>failed</em> rollback can also leave a transaction
 * open, so {@link #recoverConnection()} discards it before anything is asserted.
 */
@DisplayName("EOP-163 join-code rollback guard on H2")
class JoinCodeRollbackGuardTest {

    /** Unique in-memory database name — does not collide with the suite's eop-test. */
    private static final String JDBC_URL =
            "jdbc:h2:mem:join-code-rollback-guard;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /** The master changelog; the whole chain is applied so the guard runs in its real position. */
    private static final String CHANGELOG_MASTER = "db/changelog/db.changelog-master.xml";

    /**
     * The changelog whose rollback the guard protects. Rolling back to this file inclusive also
     * unwinds the guard changeset that sorts after it, which is exactly what must be exercised.
     */
    private static final String CHANGELOG_JOIN_CODE_WIDENING = "2026-08-22--widen-join-code-to-8-characters.xml";

    /**
     * Fewest changesets a rollback reaching {@link #CHANGELOG_JOIN_CODE_WIDENING} may legitimately
     * cover: the widening's own two plus the one guard changeset applied after it.
     *
     * <p>This is a floor asserted by {@link #changesetsAppliedFrom}, not the depth used — the helper
     * counts what {@code DATABASECHANGELOG} really holds, so a changelog appended after the guard is
     * absorbed automatically instead of shifting what the rollback reaches. Before EOP-163 the
     * equivalent floor was two; the guard changeset is precisely why it is now three, and why a
     * literal depth would have been wrong the moment the fix landed.
     */
    private static final int WIDENING_ONWARDS_CHANGESETS = 3;

    /**
     * The guard constraint, in the lower case the DDL declares. H2 folds it to upper case and
     * PostgreSQL to lower, so every assertion against it is case-insensitive.
     */
    private static final String GUARD_CONSTRAINT_NAME = "ck_eop163_join_code_fits_varchar6";

    /** Table name as H2 stores it — unquoted identifiers are folded to UPPER case. */
    private static final String TABLE_GAME_SESSION = "GAME_SESSION";

    /** Column name as H2 stores it — unquoted identifiers are folded to UPPER case. */
    private static final String COLUMN_JOIN_CODE = "JOIN_CODE";

    /**
     * A genuine eight-character code that does <em>not</em> end {@code 00}, so changeset 002's
     * rollback predicate would skip it and the old failure mode on H2 was 001's narrowing raising
     * {@code Value too long for column}.
     */
    private static final String JOIN_CODE_GENUINE_WIDE = "ABCDEFGH";

    /**
     * A genuine eight-character code that <em>does</em> end {@code 00}, so changeset 002's rollback
     * predicate matched it and the old failure mode on H2 was silent truncation followed by a
     * narrowing that succeeded — data destroyed, rollback reported clean.
     */
    private static final String JOIN_CODE_PADDED_LOOKALIKE = "ABCDEF00";

    /** A legacy six-character code: short enough to survive the narrowing, so the guard must allow it. */
    private static final String JOIN_CODE_LEGACY = "QRSTUV";

    /** What the forward migration's padding must turn {@link #JOIN_CODE_LEGACY} into. */
    private static final String JOIN_CODE_LEGACY_PADDED = "QRSTUV00";

    /** Column width after the widening. */
    private static final int WIDE_COLUMN_LENGTH = 8;

    /** Column width before the widening, and after a rollback that is allowed to complete. */
    private static final int NARROW_COLUMN_LENGTH = 6;

    private Connection connection;
    private Liquibase liquibase;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, "sa", "");
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
        // Drop the in-memory database so the next test run starts clean.
        try (Connection dropConn = DriverManager.getConnection(JDBC_URL, "sa", "");
             var stmt = dropConn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS DELETE FILES");
        } catch (SQLException ignored) {
            // Best-effort cleanup; H2 mem databases are discarded when the last connection closes.
        }
    }

    @Test
    @DisplayName("refuses the rollback when a genuine eight-character join code exists, destroying nothing")
    void refusesRollbackWhenAGenuineEightCharacterCodeExists() throws Exception {
        // Arrange — full chain applied, then a live eight-character code the narrowing could not hold.
        update();
        insertSessionWithJoinCode(JOIN_CODE_GENUINE_WIDE);
        final int depth = changesetsAppliedFrom(connection, CHANGELOG_JOIN_CODE_WIDENING, WIDENING_ONWARDS_CHANGESETS);

        // Act — the guard's ADD CONSTRAINT must fail and abort the whole rollback.
        final Throwable thrown = catchThrowable(this::rollbackPastTheWidening);
        recoverConnection();

        // Assert — it threw, and the failure names the guard rather than some incidental error.
        assertThat(thrown)
                .as("rolling back %d changesets with a live %s code must fail, not silently truncate it",
                        depth, JOIN_CODE_GENUINE_WIDE)
                .isInstanceOf(LiquibaseException.class);
        assertThat(messageChain(thrown))
                .as("the failure must name the guard constraint; H2 upper-cases it and PostgreSQL lower-cases it, "
                        + "so this is matched case-insensitively")
                .containsIgnoringCase(GUARD_CONSTRAINT_NAME);

        // Assert — nothing was destroyed: the code is whole and the column is still wide.
        assertThat(readSingleJoinCode())
                .as("the refused rollback must leave the join code byte-for-byte intact")
                .isEqualTo(JOIN_CODE_GENUINE_WIDE);
        assertThat(joinCodeColumnLength())
                .as("the refused rollback must not have narrowed the column")
                .isEqualTo(WIDE_COLUMN_LENGTH);

        // Assert — the guard left no residue: the CHECK it tried to add is not on the table.
        assertThat(guardConstraintExists())
                .as("a failed ADD CONSTRAINT must leave no constraint behind")
                .isFalse();
    }

    @Test
    @DisplayName("still rolls back losslessly when no wide join code exists, and the widening re-applies")
    void rollsBackLosslesslyWhenNoWideCodeExists() throws Exception {
        // Arrange — insert AFTER the migration, so the column is VARCHAR(8) but the value is short:
        // there is no wide code, which is the case the guard must continue to permit.
        update();
        insertSessionWithJoinCode(JOIN_CODE_LEGACY);

        // Act — roll back past the widening; the guard's ADD/DROP pair must both succeed.
        rollbackPastTheWidening();

        // Assert — the code is untouched and the column really did narrow.
        assertThat(readSingleJoinCode())
                .as("a rollback the guard permits must not alter a six-character code")
                .isEqualTo(JOIN_CODE_LEGACY);
        assertThat(joinCodeColumnLength())
                .as("the widening must have unwound — the guard adds no schema of its own and must "
                        + "not block the narrowing when every code fits")
                .isEqualTo(NARROW_COLUMN_LENGTH);

        // Act — re-apply, proving the guard leaves the chain replayable.
        update();

        // Assert — the forward padding runs again and the column widens again.
        assertThat(readSingleJoinCode())
                .as("re-applying the widening must pad the six-character code back to eight")
                .isEqualTo(JOIN_CODE_LEGACY_PADDED);
        assertThat(joinCodeColumnLength())
                .as("re-applying the widening must widen the column again")
                .isEqualTo(WIDE_COLUMN_LENGTH);
    }

    @Test
    @DisplayName("rolls back cleanly on an empty database — the guard is data-conditional, not a blanket refusal")
    void rollsBackCleanlyOnAnEmptyDatabase() throws Exception {
        // Arrange — full chain applied, game_session deliberately empty.
        update();
        assertThat(countSessions())
                .as("this test must exercise the empty-table case, so no session may be seeded")
                .isZero();

        // Act
        rollbackPastTheWidening();

        // Assert — the trivially-safe case is allowed through and the narrowing happened.
        assertThat(joinCodeColumnLength())
                .as("with no rows at all the guard has nothing to violate, so the widening must unwind")
                .isEqualTo(NARROW_COLUMN_LENGTH);
    }

    /**
     * Refuses a code ending {@code '00'} too.
     *
     * <p>This is not a duplicate of
     * {@link #refusesRollbackWhenAGenuineEightCharacterCodeExists()} even though the new behaviour is
     * identical, because the <em>old</em> behaviour was not: {@code ABCDEFGH} misses changeset 002's
     * {@code LIKE '%00'} predicate and so used to reach 001's narrowing, where H2 raised
     * {@code Value too long for column}; {@code ABCDEF00} matches that predicate, so on H2 it used to
     * be silently truncated to six characters and the narrowing then <em>succeeded</em> — data
     * destroyed, rollback reported clean, exactly PostgreSQL's failure mode. Pinning both proves the
     * guard collapses two distinct old outcomes into one refusal, and it pins the claim that makes
     * refusal the only option: after the forward migration a padded code is byte-for-byte
     * indistinguishable from a genuinely generated one, so no predicate could have separated them.
     *
     * <p>{@code PostgresRollbackRoundTripIT} covers the same input on PostgreSQL, but that class needs
     * Testcontainers and Failsafe. This assertion is the one that costs a single extra insert here and
     * is the reason H2 was never safe either, so it earns its place in the fast suite.
     */
    @Test
    @DisplayName("refuses the rollback for a genuine code ending in 00, which padding made indistinguishable")
    void refusesRollbackForAGenuineCodeEndingInZeros() throws Exception {
        // Arrange — a code that changeset 002's rollback predicate would have matched and truncated.
        update();
        insertSessionWithJoinCode(JOIN_CODE_PADDED_LOOKALIKE);

        // Act
        final Throwable thrown = catchThrowable(this::rollbackPastTheWidening);
        recoverConnection();

        // Assert — refused, and named as the guard's refusal.
        assertThat(thrown)
                .as("a genuine code ending 00 is indistinguishable from a padded one, so the rollback "
                        + "must refuse rather than truncate it")
                .isInstanceOf(LiquibaseException.class);
        assertThat(messageChain(thrown))
                .as("the failure must name the guard constraint, matched case-insensitively")
                .containsIgnoringCase(GUARD_CONSTRAINT_NAME);

        // Assert — the code survived intact rather than being silently shortened to six characters.
        assertThat(readSingleJoinCode())
                .as("the refused rollback must not have truncated the code to its first six characters")
                .isEqualTo(JOIN_CODE_PADDED_LOOKALIKE);
        assertThat(joinCodeColumnLength())
                .as("the refused rollback must not have narrowed the column")
                .isEqualTo(WIDE_COLUMN_LENGTH);
    }

    // -------------------------------------------------------------------------
    // Liquibase drivers
    // -------------------------------------------------------------------------

    /**
     * Applies the whole master changelog and re-enables auto-commit.
     *
     * <p>Liquibase leaves the connection with auto-commit disabled, so without this the
     * {@code information_schema} queries that follow would read from inside an open transaction.
     */
    private void update() throws LiquibaseException, SQLException {
        liquibase.update(new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
    }

    /**
     * Rolls back every changeset applied at or after the join-code widening — which, because the
     * guard changelog sorts after it, unwinds the guard first and therefore lets the guard veto the
     * two destructive steps below it.
     *
     * <p>The depth is recomputed on every call rather than cached, because {@code DATABASECHANGELOG}
     * only exists once {@link #update()} has run and its contents change across a re-apply.
     */
    private void rollbackPastTheWidening() throws LiquibaseException, SQLException {
        final int depth = changesetsAppliedFrom(connection, CHANGELOG_JOIN_CODE_WIDENING, WIDENING_ONWARDS_CHANGESETS);
        liquibase.rollback(depth, new Contexts(), new LabelExpression());
        connection.setAutoCommit(true);
    }

    /**
     * Discards any transaction a failed rollback left open and restores auto-commit, so the
     * assertions that follow a refusal read committed state on a usable connection.
     *
     * <p>Guarded on {@code getAutoCommit()} because calling {@code rollback()} while auto-commit is
     * on is a JDBC error rather than a no-op.
     */
    private void recoverConnection() throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /**
     * Counts the changesets that must be rolled back to undo {@code changelogFilename} and everything
     * applied after it.
     *
     * <p>Liquibase counts backwards from the most recent changeset, so a literal count is only correct
     * until someone appends a changelog. Taking every row at or after the target file's own
     * {@code ORDEREXECUTED} makes the depth self-adjusting. Copied from
     * {@code DeckTrimMigrationRoundTripTest#changesetsAppliedFrom}, which mirrors
     * {@code SessionExpiryMigrationTest#changesetsAppliedFrom006}.
     *
     * @param conn              an open connection to the migrated database
     * @param changelogFilename the changelog file to roll back to, matched as a path suffix
     * @param ownChangesets     the fewest changesets the rollback may legitimately cover
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
                        .as("rolling back to %s requires at least its own %d changesets; a smaller count means "
                                + "DATABASECHANGELOG was not populated as expected", changelogFilename, ownChangesets)
                        .isGreaterThanOrEqualTo(ownChangesets);
                return count;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fixture and query helpers
    // -------------------------------------------------------------------------

    /**
     * Inserts one {@code game_session} row carrying the given join code.
     *
     * <p>Inlined rather than delegating to {@code MigrationTestFixtures#insertMinimalGameSession},
     * which derives the code from the row's own UUID at {@code JoinCode#LENGTH} width and so cannot
     * produce a caller-chosen value — and this test's whole subject is the specific value in the
     * column. {@code MigrationTestFixtures} is shared with several constraint tests, so it is not
     * modified to take one.
     *
     * <p>The NOT NULL columns are {@code id}, {@code join_code}, {@code status}, {@code created_at},
     * {@code updated_at} and {@code version} (003-session-lifecycle.xml). {@code version} has a
     * column default but is supplied explicitly rather than relying on H2's default evaluation.
     * {@code expires_at} is NOT NULL too (006-session-expiry.xml) but carries a database-side default,
     * so it is deliberately omitted — the same choice {@code MigrationTestFixtures} makes.
     */
    private void insertSessionWithJoinCode(final String joinCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO game_session (id, join_code, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'LOBBY', NOW(), NOW(), 0)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, joinCode);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the join code of the only {@code game_session} row.
     *
     * <p>Fails rather than returning null if the row has vanished, because a rollback that deleted the
     * session instead of refusing would otherwise read as a passing assertion about a missing value.
     */
    private String readSingleJoinCode() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT join_code FROM game_session");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next())
                    .as("the seeded game_session row must still exist")
                    .isTrue();
            final String joinCode = rs.getString(1);
            assertThat(rs.next())
                    .as("exactly one game_session row must exist — a second would make this assertion ambiguous")
                    .isFalse();
            return joinCode;
        }
    }

    /** Returns the number of rows in {@code game_session}. */
    private int countSessions() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM game_session");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("count query must return a row").isTrue();
            return rs.getInt(1);
        }
    }

    /**
     * Returns the declared character length of {@code game_session.join_code}.
     *
     * <p>H2 folds unquoted identifiers to UPPER case — the opposite of PostgreSQL's lower — so the
     * {@code information_schema} lookup uses upper-case names. This is the assertion that
     * distinguishes "the rollback was refused" from "the rollback silently truncated and succeeded":
     * the column type is what changeset 001's rollback alters.
     */
    private int joinCodeColumnLength() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, TABLE_GAME_SESSION);
            ps.setString(2, COLUMN_JOIN_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("%s.%s must exist in information_schema.columns", TABLE_GAME_SESSION, COLUMN_JOIN_CODE)
                        .isTrue();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Returns {@code true} if the guard's CHECK constraint is present on {@code game_session}.
     *
     * <p>Used to prove a <em>failed</em> ADD CONSTRAINT left nothing behind. The name is compared
     * case-insensitively because H2 stores it upper-cased.
     */
    private boolean guardConstraintExists() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE UPPER(table_name) = ? AND UPPER(constraint_name) = ?")) {
            ps.setString(1, TABLE_GAME_SESSION);
            ps.setString(2, GUARD_CONSTRAINT_NAME.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("constraint lookup must return a row").isTrue();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Flattens a throwable and its causes into one string.
     *
     * <p>Liquibase wraps the driver's exception, and which layer carries the constraint name is an
     * implementation detail of the version in use — so the whole chain is searched rather than only
     * {@code getMessage()}. The depth is capped so a self-referencing cause cannot hang the suite.
     */
    private static String messageChain(final Throwable thrown) {
        final StringBuilder text = new StringBuilder();
        Throwable current = thrown;
        int depth = 0;
        while (current != null && depth < 20) {
            text.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
            current = current.getCause();
            depth++;
        }
        return text.toString();
    }
}
