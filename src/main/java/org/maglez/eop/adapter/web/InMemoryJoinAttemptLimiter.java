package org.maglez.eop.adapter.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.maglez.eop.entity.JoinCode;
import org.maglez.eop.usecase.JoinAttemptLimiter;
import org.maglez.eop.usecase.TooManyJoinAttemptsException;
import org.springframework.stereotype.Component;

/**
 * Counts failed join attempts in a sliding window, in this process's memory.
 *
 * <p>This is a primary security control, not a courtesy about load. A join code is
 * six characters over a thirty-two symbol alphabet: about thirty bits, or a billion
 * possibilities. That is unguessable only while guessing is slow. Ten failures a
 * minute allows roughly fourteen thousand guesses a day from one address, against a
 * keyspace of a billion and a handful of codes live at any moment — so the expected
 * time to stumble onto a real session is measured in centuries. Remove this class
 * and that becomes minutes. Any change that weakens it is a security change and
 * should be reviewed as one (ADR-019).
 *
 * <p><strong>Only failures are counted.</strong> A facilitator shares one code with
 * five people who all join successfully, and none of them should be throttled. An
 * attacker walking the keyspace produces nothing but misses. Counting successes
 * would penalise the first case to no benefit in the second.
 *
 * <p><strong>Two windows, not one.</strong> The address window stops one client
 * trying many codes. The code window stops many clients trying one code, which is
 * what a shared or leaked partial code looks like. Either window alone leaves the
 * other attack open.
 *
 * <p><strong>The code key is normalised before it is counted.</strong> Counting the
 * raw string would let an attacker vary the case, or substitute an {@code O} for a
 * zero, to be handed a fresh bucket for every guess — which is the same as having no
 * per-code limit at all. Anything that could never be a code shares a single bucket,
 * because those attempts are still attempts.
 *
 * <p><strong>The counters are in memory and are forgotten on restart.</strong> This
 * is an accepted risk rather than an oversight: a restart is a deployment, and a
 * deployment is not something an attacker can trigger. A shared store would be the
 * right answer for more than one instance, and there is one instance (ADR-012).
 */
@Component
public class InMemoryJoinAttemptLimiter implements JoinAttemptLimiter {

    /** How far back failures are remembered. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Failed attempts one client may make in a window before being refused. */
    private static final int MAX_FAILURES_PER_ADDRESS = 10;

    /**
     * Failed attempts against one code, from all clients together, per window.
     *
     * <p>Higher than the per-address allowance because several people mistyping the
     * same code in the same minute is an ordinary meeting, not an attack.
     */
    private static final int MAX_FAILURES_PER_CODE = 30;

    /**
     * Upper bound on tracked keys, so an attacker cannot turn a rate limiter into a
     * memory leak by presenting a new code on every request.
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    /** Bucket for attempts whose text could never be a join code. */
    private static final String MALFORMED_KEY = "\u0000malformed";

    /** Bucket for a request that arrived with no usable client address. */
    private static final String UNKNOWN_ADDRESS_KEY = "\u0000unknown";

    private final Map<String, Deque<Instant>> failuresByAddress = new ConcurrentHashMap<>();

    private final Map<String, Deque<Instant>> failuresByCode = new ConcurrentHashMap<>();

    private final Clock clock;

    InMemoryJoinAttemptLimiter(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public void checkAllowed(final String clientAddress, final String joinCodeAttempt) {
        final Instant now = clock.instant();
        final Instant horizon = now.minus(WINDOW);

        refuseIfExhausted(failuresByAddress.get(addressKey(clientAddress)), horizon, MAX_FAILURES_PER_ADDRESS);
        refuseIfExhausted(failuresByCode.get(codeKey(joinCodeAttempt)), horizon, MAX_FAILURES_PER_CODE);
    }

    @Override
    public void recordFailure(final String clientAddress, final String joinCodeAttempt) {
        final Instant now = clock.instant();
        remember(failuresByAddress, addressKey(clientAddress), now);
        remember(failuresByCode, codeKey(joinCodeAttempt), now);
    }

    /**
     * Refuses the attempt when a window is already full.
     *
     * <p>The wait reported is the time until the oldest remembered failure leaves
     * the window, which is the earliest moment the caller could succeed. Reporting
     * the whole window instead would be a lie in the caller's favour, and reporting
     * nothing would leave a well-behaved client guessing.
     */
    private void refuseIfExhausted(final Deque<Instant> window, final Instant horizon, final int allowance) {
        if (window == null) {
            return;
        }
        prune(window, horizon);
        if (window.size() < allowance) {
            return;
        }
        final Instant oldest = window.peekFirst();
        final Duration remaining = oldest == null ? WINDOW : Duration.between(horizon, oldest);
        throw new TooManyJoinAttemptsException(remaining.isNegative() || remaining.isZero()
                ? Duration.ofSeconds(1)
                : remaining);
    }

    private void remember(final Map<String, Deque<Instant>> windows, final String key, final Instant now) {
        if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
            evictEmptyWindows(windows, now.minus(WINDOW));
            if (windows.size() >= MAX_TRACKED_KEYS) {
                // Nothing left to reclaim. The other window still applies, and
                // dropping this record is preferable to unbounded growth.
                return;
            }
        }
        final Deque<Instant> window = windows.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        window.addLast(now);
        prune(window, now.minus(WINDOW));
    }

    /**
     * Drops failures that have aged out of the window.
     *
     * <p>Pruning on access rather than on a schedule keeps the cost proportional to
     * traffic: a key nobody touches costs one map entry until it is evicted.
     */
    private static void prune(final Deque<Instant> window, final Instant horizon) {
        for (Instant oldest = window.peekFirst();
                oldest != null && oldest.isBefore(horizon);
                oldest = window.peekFirst()) {
            window.pollFirst();
        }
    }

    private static void evictEmptyWindows(final Map<String, Deque<Instant>> windows, final Instant horizon) {
        windows.values().forEach(window -> prune(window, horizon));
        windows.values().removeIf(Collection::isEmpty);
    }

    private static String addressKey(final String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? UNKNOWN_ADDRESS_KEY : clientAddress;
    }

    private static String codeKey(final String joinCodeAttempt) {
        return JoinCode.parse(joinCodeAttempt).map(JoinCode::value).orElse(MALFORMED_KEY);
    }
}
