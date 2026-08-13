package org.maglez.eop.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.adapter.persistence.TrickPlayRepositoryAdapter.seatRead;
import static org.maglez.eop.adapter.persistence.TrickPlayRepositoryAdapter.seatToWrite;

import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.maglez.eop.entity.GameSession;

/**
 * Pins the two seat guards in {@link TrickPlayRepositoryAdapter}.
 *
 * <p>These are the only guards in the adapter that no integration test can reach, and
 * the reason is the same in both directions. {@code seatRead} exists to survive a row
 * that {@code chk_game_session_current_leader_seat} now refuses, so the row cannot be
 * manufactured; {@code seatToWrite}'s negative limb is only reachable from a use case
 * that has not been written yet, because every production caller today hands it a seat a
 * domain object already bounded. Proving them through the adapter would mean dropping
 * the constraint, which would leave the suite measuring the guard against a schema the
 * application never runs on.
 *
 * <p>So they are proved here instead, as the pure functions they are: no Spring, no
 * database, sub-millisecond. What this cannot prove is the translation the adapter
 * applies on the way out — that an {@link IllegalStateException} leaving a {@code
 * @Repository} reaches the catch-all 500 rather than the handler that answers 400. That
 * half is pinned by {@code TrickPlayRepositoryAdapterIntegrationTest}, which asserts it
 * on the sibling paths that do throw {@link IllegalStateException} through a real
 * context.
 *
 * <p>Both bounds are asserted against {@link GameSession#MAXIMUM_PLAYERS} rather than
 * against the literal 5, so widening the table widens these tests with it instead of
 * turning them red for the wrong reason.
 */
@DisplayName("Seat bounds")
class SeatBoundsTest {

    private static final int HIGHEST_SEAT = GameSession.MAXIMUM_PLAYERS - 1;

    @Nested
    @DisplayName("a seat read from a column")
    class ASeatReadFromAColumn {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 5})
        @DisplayName("is returned when the session could seat it")
        void isReturnedWhenTheSessionCouldSeatIt(final int seatOrder) {
            assertThat(seatRead(seatOrder)).isEqualTo(OptionalInt.of(seatOrder));
        }

        @Test
        @DisplayName("is returned at the highest seat a table has")
        void isReturnedAtTheHighestSeatATableHas() {
            assertThat(seatRead(HIGHEST_SEAT)).isEqualTo(OptionalInt.of(HIGHEST_SEAT));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 6, 9, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("is refused as our corruption when no seat could hold it")
        void isRefusedAsOurCorruptionWhenNoSeatCouldHoldIt(final int seatOrder) {
            // IllegalStateException, not IllegalArgumentException: the caller supplied
            // nothing here. The value came out of our own column, so the fault is ours,
            // and the type is what decides whether the answer blames us or them.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> seatRead(seatOrder))
                    .withMessage("Leader seat " + seatOrder + " is outside the seats a session has");
        }

        @Test
        @DisplayName("is refused one seat above the highest a table has")
        void isRefusedOneSeatAboveTheHighestATableHas() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the bound has to move with the table, not with a literal")
                    .isThrownBy(() -> seatRead(GameSession.MAXIMUM_PLAYERS));
        }
    }

    @Nested
    @DisplayName("a seat bound for a column")
    class ASeatBoundForAColumn {

        @ParameterizedTest
        @ValueSource(ints = {0, 3, 5})
        @DisplayName("is passed through when the session could seat it")
        void isPassedThroughWhenTheSessionCouldSeatIt(final int seatOrder) {
            assertThat(seatToWrite(seatOrder, "openingLeaderSeat")).isEqualTo(seatOrder);
        }

        @Test
        @DisplayName("is passed through at the highest seat a table has")
        void isPassedThroughAtTheHighestSeatATableHas() {
            assertThat(seatToWrite(HIGHEST_SEAT, "nextLeaderSeat")).isEqualTo(HIGHEST_SEAT);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 6, 9, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("is refused before it reaches the statement")
        void isRefusedBeforeItReachesTheStatement(final int seatOrder) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> seatToWrite(seatOrder, "openingLeaderSeat"))
                    .withMessage(
                            "openingLeaderSeat " + seatOrder + " is outside the seats a session has");
        }

        @Test
        @DisplayName("names the parameter it arrived as, so the two are told apart")
        void namesTheParameterItArrivedAs() {
            // The message is the only thing that distinguishes which of two seats was
            // wrong, and both reach the same column through different arguments.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> seatToWrite(9, "nextLeaderSeat"))
                    .withMessage("nextLeaderSeat 9 is outside the seats a session has");
        }

        @Test
        @DisplayName("is refused one seat above the highest a table has")
        void isRefusedOneSeatAboveTheHighestATableHas() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the bound has to move with the table, not with a literal")
                    .isThrownBy(
                            () -> seatToWrite(GameSession.MAXIMUM_PLAYERS, "openingLeaderSeat"));
        }
    }
}
