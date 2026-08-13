package org.maglez.eop.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the identifier accessors on the exception types that carry one.
 *
 * <p>These accessors had no test at all. A quality gate measured the four session-bearing
 * types at 75% instruction coverage and showed the gap was exactly the accessor body, so a
 * mutation replacing {@code return sessionId} with {@code return null} survived the whole
 * suite. That mutation is not cosmetic. The accessor is the only way a caller recovers the
 * identifier from a caught exception, and the {@code GlobalExceptionHandler} builds its
 * problem details from accessors rather than from {@code getMessage()} precisely so it can
 * choose which identifiers to disclose. An accessor returning null would put a literal
 * "null" in a response body, or throw inside the handler and turn a mapped 404 or 409 into
 * a 500.
 *
 * <p>These are innermost-layer types with no framework imports, so this test needs no
 * Spring context and runs in microseconds.
 */
@DisplayName("an exception carrying an identifier")
class SessionBearingExceptionTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-00000000a5e5");
    private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

    /**
     * The four types whose only state is the session identifier. Each entry pairs the
     * constructor with the accessor so that adding a fifth such type is one line.
     */
    private static List<Object[]> sessionBearingTypes() {
        return List.of(
                entry(
                        "HandAlreadyDealtException",
                        HandAlreadyDealtException::new,
                        HandAlreadyDealtException::sessionId),
                entry(
                        "HandNotDealtException",
                        HandNotDealtException::new,
                        HandNotDealtException::sessionId),
                entry(
                        "PlayerNotInSessionException",
                        PlayerNotInSessionException::new,
                        PlayerNotInSessionException::sessionId),
                entry(
                        "SessionNotFoundException",
                        SessionNotFoundException::new,
                        SessionNotFoundException::sessionId));
    }

    private static <E extends RuntimeException> Object[] entry(
            final String name,
            final Function<UUID, E> constructor,
            final Function<E, UUID> accessor) {
        final Function<UUID, UUID> roundTrip = sessionId -> accessor.apply(constructor.apply(sessionId));
        return new Object[] {name, roundTrip};
    }

    @ParameterizedTest(name = "{0} returns the identifier it was constructed with")
    @MethodSource("sessionBearingTypes")
    @DisplayName("returns the session identifier it was constructed with")
    void shouldReturnTheSessionIdentifier(final String name, final Function<UUID, UUID> roundTrip) {
        final UUID recovered = roundTrip.apply(SESSION_ID);

        assertThat(recovered)
                .as("%s must hand back the identifier a caller needs to act on the failure", name)
                .isEqualTo(SESSION_ID);
    }

    /**
     * Pins the accessor to the constructor argument rather than to a constant. Asserting
     * against one fixed identifier would pass against an accessor hard-coded to return
     * that identifier, so this asserts two distinct instances disagree.
     */
    @ParameterizedTest(name = "{0} does not share state between instances")
    @MethodSource("sessionBearingTypes")
    @DisplayName("keeps each instance's identifier to itself")
    void shouldNotShareStateBetweenInstances(
            final String name, final Function<UUID, UUID> roundTrip) {
        final UUID other = UUID.fromString("00000000-0000-7000-8000-00000000dead");

        assertThat(roundTrip.apply(SESSION_ID))
                .as("%s must not return a constant", name)
                .isNotEqualTo(roundTrip.apply(other));
    }

    @Test
    @DisplayName("names the session in its message, so a log line identifies the session")
    void shouldNameTheSessionInTheMessage() {
        assertThat(new HandAlreadyDealtException(SESSION_ID).getMessage())
                .contains(SESSION_ID.toString());
        assertThat(new HandNotDealtException(SESSION_ID).getMessage())
                .contains(SESSION_ID.toString());
    }

    /**
     * The 404 parity that hides membership behind absence. A stranger holding a guessed
     * identifier must be answered exactly as a caller naming a session that does not
     * exist, and the handler builds both details from {@code getMessage()}, so the parity
     * lives in these two constructors. {@code GlobalExceptionHandlerTest} asserts the two
     * problem details are equal; this asserts the messages they are built from are, which
     * is where a divergence would actually be introduced.
     */
    @Test
    @DisplayName("says nothing a missing session would not say, when the caller is a stranger")
    void shouldAnswerAStrangerAsAMissingSessionWouldAnswer() {
        assertThat(new PlayerNotInSessionException(SESSION_ID).getMessage())
                .as("a different sentence here is as good an oracle for membership as a 403")
                .isEqualTo(new SessionNotFoundException(SESSION_ID).getMessage());
    }

    @Test
    @DisplayName("returns the trick identifier it was constructed with, when it carries one")
    void shouldReturnTheTrickIdentifier() {
        final TrickAlreadyResolvedException exception = new TrickAlreadyResolvedException(TRICK_ID);

        assertThat(exception.trickId()).isEqualTo(TRICK_ID);
        assertThat(exception.getMessage()).contains(TRICK_ID.toString());
    }
}
