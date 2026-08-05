package org.maglez.eop.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.entity.JoinCode;
import org.maglez.eop.usecase.TooManyJoinAttemptsException;

/**
 * The throttle is a security control, so its boundaries are asserted rather than
 * assumed (ADR-019).
 *
 * <p>A join code is about thirty bits. That is unguessable only while guessing is
 * slow, so an off-by-one that allowed a hundred attempts a minute instead of ten
 * would not break a single feature and would not be noticed — it would simply move
 * the expected time to stumble onto a live session from centuries to weeks. Every
 * test here pins a number that a reviewer would otherwise have to take on trust.
 *
 * <p>Time is injected rather than waited for. A test that slept for a minute to
 * watch a window expire would be a minute of build time spent proving arithmetic.
 */
@DisplayName("InMemoryJoinAttemptLimiter")
class InMemoryJoinAttemptLimiterTest {

    /** Mirrors the production window. Pinning it is the point of these tests. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Mirrors {@code MAX_FAILURES_PER_ADDRESS}, which is private. */
    private static final int PER_ADDRESS = 10;

    /** Mirrors {@code MAX_FAILURES_PER_CODE}, which is private. */
    private static final int PER_CODE = 30;

    /** Mirrors {@code MAX_TRACKED_KEYS}, which is private. */
    private static final int TRACKED_KEYS = 10_000;

    private static final Instant START = Instant.parse("2026-02-01T09:30:00Z");

    private static final String ADDRESS = "203.0.113.9";

    private static final String CODE = "ABC230";

    private final TickingClock clock = new TickingClock(START);

    private final InMemoryJoinAttemptLimiter limiter = new InMemoryJoinAttemptLimiter(clock);

    @Nested
    @DisplayName("counting failures from one address")
    class TheAddressWindow {

        @Test
        @DisplayName("ten failures are tolerated and the eleventh attempt is refused")
        void shouldRefuseTheEleventhAttempt() {
            for (int failure = 0; failure < PER_ADDRESS - 1; failure++) {
                limiter.recordFailure(ADDRESS, CODE);
            }

            assertThatCode(() -> limiter.checkAllowed(ADDRESS, CODE))
                    .as("the tenth attempt is still allowed")
                    .doesNotThrowAnyException();

            limiter.recordFailure(ADDRESS, CODE);

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS, CODE));
        }

        @Test
        @DisplayName("the refusal names the wait until the oldest failure leaves the window")
        void shouldReportHowLongToWait() {
            exhaustTheAddressWindow();

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS, CODE))
                    .satisfies(refusal -> assertThat(refusal.retryAfter()).isEqualTo(WINDOW));
        }

        @Test
        @DisplayName("a failure is forgotten only once it is strictly older than the window")
        void shouldHoldTheWindowOpenForItsFullMinute() {
            exhaustTheAddressWindow();

            clock.advance(WINDOW);

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .as("a failure exactly a minute old is still inside the window")
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS, CODE));

            clock.advance(Duration.ofSeconds(1));

            assertThatCode(() -> limiter.checkAllowed(ADDRESS, CODE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("successful joins are never counted, so a facilitator's five guests are not throttled")
        void shouldNotCountSuccesses() {
            assertThatCode(() -> {
                for (int attempt = 0; attempt < PER_ADDRESS * 10; attempt++) {
                    limiter.checkAllowed(ADDRESS, CODE);
                }
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("one exhausted address does not throttle another")
        void shouldKeepAddressesApart() {
            exhaustTheAddressWindow();

            assertThatCode(() -> limiter.checkAllowed("198.51.100.4", CODE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a request with no usable address shares one bucket, so it cannot be used to escape the limit")
        void shouldFoldMissingAddressesIntoOneBucket() {
            for (int failure = 0; failure < PER_ADDRESS / 2; failure++) {
                limiter.recordFailure(null, CODE);
                limiter.recordFailure("   ", CODE);
            }

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed(null, CODE));
            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed("", CODE));
        }
    }

    @Nested
    @DisplayName("counting failures against one code")
    class TheCodeWindow {

        @Test
        @DisplayName("thirty failures against one code refuse even a client that has never tried before")
        void shouldRefuseTheThirtyFirstAttemptOnACode() {
            for (int failure = 0; failure < PER_CODE; failure++) {
                limiter.recordFailure("192.0.2." + failure, CODE);
            }

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed("198.51.100.7", CODE));
        }

        @Test
        @DisplayName("case and the letter O are normalised before counting, so respelling a guess buys nothing")
        void shouldCountEverySpellingOfACodeTogether() {
            final String[] spellings = {CODE, "abc230", "ABC23O", " abc23o "};
            for (int failure = 0; failure < PER_CODE; failure++) {
                limiter.recordFailure("192.0.2." + failure, spellings[failure % spellings.length]);
            }

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed("198.51.100.7", CODE));
        }

        @Test
        @DisplayName("text that could never be a code shares one bucket, because a miss is still an attempt")
        void shouldFoldMalformedAttemptsIntoOneBucket() {
            for (int failure = 0; failure < PER_CODE; failure++) {
                limiter.recordFailure("192.0.2." + failure, "not-a-code-" + failure);
            }

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed("198.51.100.7", "!!!"));
        }

        @Test
        @DisplayName("one exhausted code does not throttle another")
        void shouldKeepCodesApart() {
            for (int failure = 0; failure < PER_CODE; failure++) {
                limiter.recordFailure("192.0.2." + failure, CODE);
            }

            assertThatCode(() -> limiter.checkAllowed("198.51.100.7", "DEF567")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("bounding its own memory")
    class TrackedKeys {

        @Test
        @DisplayName("a flood of one-off keys stops being tracked rather than growing without bound")
        void shouldStopTrackingOnceFull() {
            floodWithDistinctKeys();

            for (int failure = 0; failure < PER_ADDRESS; failure++) {
                limiter.recordFailure(ADDRESS, CODE);
            }

            assertThatCode(() -> limiter.checkAllowed(ADDRESS, CODE))
                    .as("the flood filled the table, so these failures were dropped instead of leaking memory")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("once the flood ages out the table is reclaimed and the limit applies again")
        void shouldReclaimAgedKeys() {
            floodWithDistinctKeys();

            clock.advance(WINDOW.plusSeconds(1));
            for (int failure = 0; failure < PER_ADDRESS; failure++) {
                limiter.recordFailure(ADDRESS, CODE);
            }

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> limiter.checkAllowed(ADDRESS, CODE));
        }

        /**
         * Presents one failure from each of a full table's worth of distinct
         * addresses, each against a distinct code, so both maps reach their cap.
         */
        private void floodWithDistinctKeys() {
            for (int key = 0; key < TRACKED_KEYS; key++) {
                limiter.recordFailure("flood-" + key, codeFor(key));
            }
        }
    }

    private void exhaustTheAddressWindow() {
        for (int failure = 0; failure < PER_ADDRESS; failure++) {
            limiter.recordFailure(ADDRESS, CODE);
        }
    }

    /**
     * Builds the {@code index}th distinct canonical join code.
     *
     * <p>Written from the alphabet itself so every code survives normalisation
     * unchanged: a generator that emitted an {@code O} or a lower-case letter
     * would silently collapse two keys into one and weaken the flood.
     *
     * @param index which code to build
     * @return a valid six character code, distinct for every distinct index
     */
    private static String codeFor(final int index) {
        final int radix = JoinCode.ALPHABET.length();
        final StringBuilder code = new StringBuilder(JoinCode.LENGTH);
        int remaining = index;
        for (int position = 0; position < JoinCode.LENGTH; position++) {
            code.append(JoinCode.ALPHABET.charAt(remaining % radix));
            remaining /= radix;
        }
        return code.toString();
    }

    /**
     * A clock the test moves by hand.
     *
     * <p>{@link Clock#tick} and friends all read the wall clock. Waiting for a real
     * minute to watch a window expire would spend a minute of build time proving
     * subtraction.
     */
    private static final class TickingClock extends Clock {

        private Instant now;

        private TickingClock(final Instant start) {
            this.now = start;
        }

        private void advance(final Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
