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
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the foreign keys added in EOP-14 Slice B are enforced by the
 * database engine, not merely declared.
 *
 * <p>Tests 1 and 2 cover the original single-column player foreign keys
 * ({@code fk_trick_play_player} and {@code fk_hand_player}), which were present in
 * earlier drafts of the schema. Those keys have since been replaced by the composite
 * keys {@code fk_trick_play_player_seat} and {@code fk_hand_player_seat} (changeset 009),
 * but the enforcement they provided — rejecting a play or hand whose {@code player_id}
 * references no player row — is still provided by the composite keys (both columns are
 * NOT NULL, so MATCH SIMPLE has no partial-null escape). Tests 1 and 2 therefore remain
 * valid regression guards for that property.
 *
 * <p>Tests 3 and 4 are the load-bearing regression guards for the seat-impersonation
 * exploit that motivated changeset 009. They prove that the composite foreign keys
 * {@code fk_trick_play_player_seat} and {@code fk_hand_player_seat} reject a row whose
 * {@code seat_order} disagrees with the player's own {@code player.seat_order}. This is
 * the structural closure of the Slice A seat-impersonation defect at the storage layer:
 * a player who holds seat 1 cannot write a {@code trick_play} or {@code hand} row
 * claiming seat 0, even if seat 0 is unoccupied in that trick or session.
 *
 * <p>Both tests are non-vacuous: the "happy path" insert (at the player's own seat) is
 * performed first and must succeed, proving the schema is not simply rejecting all inserts.
 * Only then is the FK-violating insert attempted.
 *
 * <p>No Spring context. Owns its own uniquely-named in-memory H2 database.
 * {@code connection.setAutoCommit(true)} is set immediately after Liquibase runs to
 * avoid the {@code 23506}-instead-of-{@code 23505} trap (Liquibase leaves auto-commit
 * off; a second connection opened against the same database would not see uncommitted
 * parent rows and would fail on a different constraint than the one under test).
 */
@DisplayName("foreign keys on trick_play and hand are enforced by the database")
class TrickPlayForeignKeyTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:trick-play-fk;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    /**
     * SQL state H2 emits on a foreign-key violation.
     * Verified empirically: H2 2.4.240 throws
     * {@code org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException} with
     * {@code getSQLState() == "23506"} for a referential integrity violation
     * (as distinct from {@code 23505} for a unique violation).
     */
    private static final String SQL_STATE_FK_VIOLATION = "23506";

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
        // Re-enable it so that rows inserted in the test body are immediately visible and
        // so that FK checks fire against committed data rather than the transaction's own
        // snapshot, which would mask the 23506 we are testing for.
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
    // Test 1 — fk_trick_play_player_seat: trick_play.player_id must reference player
    // =========================================================================

    /**
     * A {@code trick_play} row whose {@code player_id} references no {@code player} row
     * must be rejected by {@code fk_trick_play_player_seat} with SQL state {@code 23506}.
     *
     * <p>The composite key {@code fk_trick_play_player_seat} (changeset 009) references
     * {@code player(id, seat_order)}. Both columns are NOT NULL, so MATCH SIMPLE has no
     * partial-null escape: a row with a non-existent {@code player_id} necessarily fails
     * the composite key, just as it would have failed the former single-column
     * {@code fk_trick_play_player}.
     *
     * <p>Non-vacuity: a play with a real player is inserted first and must succeed.
     * Only then is the dangling-player insert attempted.
     */
    @Test
    @DisplayName("trick_play with a player_id that references no player row is rejected by fk_trick_play_player_seat (23506)")
    void trickPlayWithNonExistentPlayerIsRejected() throws Exception {
        // Arrange — build the full FK chain with a real player (happy path, must succeed)
        final UUID cardId = MigrationTestFixtures.anyExistingCard(connection);
        final UUID trickId = MigrationTestFixtures.insertMinimalTrick(connection);
        final UUID realPlayerId = MigrationTestFixtures.insertPlayerForTrick(connection, trickId, 0);

        // Non-vacuity: the happy-path insert must succeed
        final UUID happyPlayId = MigrationTestFixtures.insertTrickPlay(connection, trickId, cardId, 0, realPlayerId);
        assertThat(happyPlayId)
                .as("a trick_play with a real player_id must be accepted — schema is not rejecting all inserts")
                .isNotNull();

        // Arrange — a second card and a player_id that exists in no player row
        final UUID cardId2 = MigrationTestFixtures.secondExistingCard(connection);
        final UUID ghostPlayerId = UUID.randomUUID(); // deliberately not inserted

        // Act + Assert — the FK-violating insert must be rejected with 23506
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlay(connection, trickId, cardId2, 1, ghostPlayerId))
                .as("trick_play with player_id=%s (no matching player row) must be rejected "
                        + "by fk_trick_play_player_seat with SQL state %s",
                        ghostPlayerId, SQL_STATE_FK_VIOLATION)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_FK_VIOLATION);
    }

    // =========================================================================
    // Test 2 — fk_hand_player_seat: hand.player_id must reference player
    // =========================================================================

    /**
     * A {@code hand} row whose {@code player_id} references no {@code player} row
     * must be rejected by {@code fk_hand_player_seat} with SQL state {@code 23506}.
     *
     * <p>The composite key {@code fk_hand_player_seat} (changeset 009) references
     * {@code player(id, seat_order)}. Both columns are NOT NULL, so MATCH SIMPLE has no
     * partial-null escape: a row with a non-existent {@code player_id} necessarily fails
     * the composite key, just as it would have failed the former single-column
     * {@code fk_hand_player}.
     *
     * <p>Non-vacuity: a hand with a real player is inserted first and must succeed.
     * Only then is the dangling-player insert attempted.
     */
    @Test
    @DisplayName("hand with a player_id that references no player row is rejected by fk_hand_player_seat (23506)")
    void handWithNonExistentPlayerIsRejected() throws Exception {
        // Arrange — a session and a real player (happy path, must succeed)
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID realPlayerId = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 0);

        // Non-vacuity: the happy-path insert must succeed
        final UUID happyHandId = MigrationTestFixtures.insertMinimalHand(connection, sessionId, realPlayerId, 0);
        assertThat(happyHandId)
                .as("a hand with a real player_id must be accepted — schema is not rejecting all inserts")
                .isNotNull();

        // Arrange — a ghost player_id that exists in no player row
        final UUID ghostPlayerId = UUID.randomUUID(); // deliberately not inserted

        // Act + Assert — the FK-violating insert must be rejected with 23506
        assertThatThrownBy(() ->
                insertHandWithGhostPlayer(connection, sessionId, ghostPlayerId, 1))
                .as("hand with player_id=%s (no matching player row) must be rejected "
                        + "by fk_hand_player_seat with SQL state %s",
                        ghostPlayerId, SQL_STATE_FK_VIOLATION)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_FK_VIOLATION);
    }

    // =========================================================================
    // Test 3 — forged-seat exploit on trick_play: fk_trick_play_player_seat
    // =========================================================================

    /**
     * A player who holds {@code player.seat_order = 1} must not be able to write a
     * {@code trick_play} row claiming {@code seat_order = 0}, even when seat 0 is
     * unoccupied in that trick.
     *
     * <p>This is the load-bearing regression guard for the seat-impersonation exploit
     * that motivated changeset 009. The composite foreign key
     * {@code fk_trick_play_player_seat} references {@code player(id, seat_order)}: a
     * row whose {@code (player_id, seat_order)} pair does not match any
     * {@code player(id, seat_order)} row is rejected with SQL state {@code 23506}.
     * Because the player holds seat 1, the pair {@code (player_id, 0)} has no matching
     * row in {@code player}, so the insert is rejected regardless of whether seat 0 is
     * occupied by another player.
     *
     * <p>Non-vacuity: the happy-path insert at the player's own seat (seat 1) is
     * performed first and must succeed, proving the schema is not simply rejecting all
     * inserts. The forged-seat insert is attempted in a separate trick (in a separate
     * session) so that {@code uq_trick_play_trick_player} (one player per trick) does
     * not fire before the FK check — the only constraint that can fire on the
     * forged-seat row is {@code fk_trick_play_player_seat}.
     */
    @Test
    @DisplayName("trick_play forged-seat exploit: player at seat 1 cannot claim seat 0 — rejected by fk_trick_play_player_seat (23506)")
    void trickPlayForgedSeatExploitIsRejected() throws Exception {
        // Arrange — two independent sessions, each with one trick; one player at seat 1
        final UUID sessionId1 = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID trickId1 = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId1);
        final UUID cardId1 = MigrationTestFixtures.anyExistingCard(connection);
        // Player holds seat 1 in session 1 — this is the player's actual seat in player.seat_order
        final UUID playerId = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId1, 1);

        // Non-vacuity: happy-path insert at the player's own seat (seat 1) in trick 1 must succeed
        final UUID happyPlayId = MigrationTestFixtures.insertTrickPlay(
                connection, trickId1, cardId1, 1, playerId);
        assertThat(happyPlayId)
                .as("a trick_play at the player's own seat (seat 1) must be accepted — "
                        + "schema is not simply rejecting all inserts")
                .isNotNull();

        // Arrange — a second session and trick for the forged-seat attempt.
        // Using a separate trick ensures uq_trick_play_trick_player does not fire first
        // (the player has no play in trick 2 yet). The only constraint that can fire is
        // fk_trick_play_player_seat, because (playerId, 0) has no matching player(id, seat_order) row.
        final UUID sessionId2 = MigrationTestFixtures.insertMinimalGameSession(connection);
        final UUID trickId2 = MigrationTestFixtures.insertMinimalTrickInSession(connection, sessionId2);
        final UUID cardId2 = MigrationTestFixtures.secondExistingCard(connection);

        // Act + Assert — forged-seat insert in trick 2: same player, different card, seat 0
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertTrickPlay(connection, trickId2, cardId2, 0, playerId))
                .as("trick_play with player_id=%s claiming seat 0 (player holds seat 1) must be "
                        + "rejected by fk_trick_play_player_seat with SQL state %s",
                        playerId, SQL_STATE_FK_VIOLATION)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_FK_VIOLATION);
    }

    // =========================================================================
    // Test 4 — forged-seat exploit on hand: fk_hand_player_seat
    // =========================================================================

    /**
     * A player who holds {@code player.seat_order = 1} must not be able to get a
     * {@code hand} row at {@code seat_order = 0}, even when seat 0 is unoccupied in
     * that session.
     *
     * <p>This is the load-bearing regression guard for the same seat-impersonation
     * exploit on the {@code hand} table. The composite foreign key
     * {@code fk_hand_player_seat} references {@code player(id, seat_order)}: a row
     * whose {@code (player_id, seat_order)} pair does not match any
     * {@code player(id, seat_order)} row is rejected with SQL state {@code 23506}.
     *
     * <p>Non-vacuity: the happy-path insert at the player's own seat (seat 1) is
     * performed first and must succeed, proving the schema is not simply rejecting all
     * inserts. Only then is the forged-seat insert attempted.
     */
    @Test
    @DisplayName("hand forged-seat exploit: player at seat 1 cannot get a hand at seat 0 — rejected by fk_hand_player_seat (23506)")
    void handForgedSeatExploitIsRejected() throws Exception {
        // Arrange — one session, one player at seat 1
        final UUID sessionId = MigrationTestFixtures.insertMinimalGameSession(connection);
        // Player holds seat 1 — this is the player's actual seat in player.seat_order
        final UUID playerId = MigrationTestFixtures.insertMinimalPlayer(connection, sessionId, 1);

        // Non-vacuity: happy-path insert at the player's own seat (seat 1) must succeed
        final UUID happyHandId = MigrationTestFixtures.insertMinimalHand(
                connection, sessionId, playerId, 1);
        assertThat(happyHandId)
                .as("a hand at the player's own seat (seat 1) must be accepted — "
                        + "schema is not simply rejecting all inserts")
                .isNotNull();

        // Act + Assert — forged-seat insert: same player, seat 0 (not the player's seat)
        // Seat 0 is unoccupied in this session, but the composite FK still rejects the row
        // because (playerId, 0) has no matching player(id, seat_order) row.
        assertThatThrownBy(() ->
                MigrationTestFixtures.insertMinimalHand(connection, sessionId, playerId, 0))
                .as("hand with player_id=%s claiming seat 0 (player holds seat 1) must be "
                        + "rejected by fk_hand_player_seat with SQL state %s",
                        playerId, SQL_STATE_FK_VIOLATION)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQL_STATE_FK_VIOLATION);
    }

    /**
     * Inserts a {@code hand} row with the given {@code player_id} without going through
     * {@link MigrationTestFixtures#insertMinimalHand}, which would create a real player.
     * Used only to prove the FK fires.
     */
    private static void insertHandWithGhostPlayer(
            final Connection conn,
            final UUID sessionId,
            final UUID ghostPlayerId,
            final int seatOrder) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO hand (id, game_session_id, player_id, seat_order) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, sessionId);
            ps.setObject(3, ghostPlayerId);
            ps.setInt(4, seatOrder);
            ps.executeUpdate();
        }
    }
}
