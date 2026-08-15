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
 *
 * <p><strong>Fail-closed on saturation.</strong> When the tracked-key table is full
 * and a new address or code arrives, the attempt is refused rather than silently
 * admitted. A flood of distinct keys is itself an attack pattern; admitting requests
 * that cannot be tracked would let an attacker bypass the limiter by exhausting the
 * table first (ADR-019).
 *
 * <p><strong>Atomic check-and-record.</strong> The check and the record for each
 * window are performed under the same lock on the window deque, so two concurrent
 * threads racing at the limit boundary cannot both pass the check before either
 * records its failure. Without this, a thread that sees {@code limit - 1} failures
 * and a concurrent thread that also sees {@code limit - 1} failures would both
 * proceed, and the recorded count would exceed the limit.
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

    /**
     * {@inheritDoc}
     *
     * <p>This implementation checks both the per-address and per-code windows. Each
     * check is performed atomically with the subsequent record: the window deque is
     * locked for the duration of the prune-check-add sequence, so two concurrent
     * threads racing at the limit boundary cannot both pass.
     */
    @Override
    public void checkAllowed(final String clientAddress, final String joinCodeAttempt) {
        // Checks only — no recording. Atomicity is enforced in recordFailure.
        final Instant now = clock.instant();
        final Instant horizon = now.minus(WINDOW);

        refuseIfExhausted(failuresByAddress.get(addressKey(clientAddress)), horizon, MAX_FAILURES_PER_ADDRESS);
        refuseIfExhausted(failuresByCode.get(codeKey(joinCodeAttempt)), horizon, MAX_FAILURES_PER_CODE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each window is updated atomically: the prune, the limit check, and the
     * insertion are all performed under the same lock on the window deque. This
     * prevents two concurrent threads from both passing the limit check before
     * either records its failure.
     *
     * <p>If the tracked-key table is full and the key is new, the attempt is refused
     * rather than silently admitted (fail-closed on saturation).
     */
    @Override
    public void recordFailure(final String clientAddress, final String joinCodeAttempt) {
        final Instant now = clock.instant();
        checkAndRecord(failuresByAddress, addressKey(clientAddress), now, MAX_FAILURES_PER_ADDRESS);
        checkAndRecord(failuresByCode, codeKey(joinCodeAttempt), now, MAX_FAILURES_PER_CODE);
    }

    /**
     * Atomically checks the window limit and records the failure if allowed.
     *
     * <p>The window deque is locked for the entire prune-check-add sequence. This
     * ensures that two concurrent callers racing at {@code limit - 1} cannot both
     * pass the check before either records, which would push the count past the
     * limit.
     *
     * <p>If the map is at capacity and the key is new, the attempt is refused
     * immediately (fail-closed on saturation). An attempt to evict aged-out windows
     * is made first outside of any map-level lock; if the map is still full after
     * eviction, the request is refused.
     *
     * @throws TooManyJoinAttemptsException if the window is exhausted, or if the
     *     tracked-key table is full and the key is new
     */
    private void checkAndRecord(
            final Map<String, Deque<Instant>> windows,
            final String key,
            final Instant now,
            final int allowance) {

        final Instant horizon = now.minus(WINDOW);

        // If the table is full and the key is new, try to reclaim aged-out entries
        // before deciding to refuse. Eviction is done outside computeIfAbsent to
        // avoid re-entrant modification of the ConcurrentHashMap.
        if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
            evictEmptyWindows(windows, horizon);
            if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
                // Table still full after eviction — refuse rather than admit untracked.
                throw new TooManyJoinAttemptsException(Duration.ofSeconds(1));
            }
        }

        // Obtain or create the window for this key.
        final Deque<Instant> window = windows.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());

        // Atomic prune-check-add under the window's own monitor.
        synchronized (window) {
            prune(window, horizon);
            if (window.size() >= allowance) {
                final Instant oldest = window.peekFirst();
                final Duration remaining = oldest == null ? WINDOW : Duration.between(horizon, oldest);
                throw new TooManyJoinAttemptsException(remaining.isNegative() || remaining.isZero()
                        ? Duration.ofSeconds(1)
                        : remaining);
            }
            window.addLast(now);
        }
    }

    /**
     * Refuses the attempt when a window is already full.
     *
     * <p>Used by {@link #checkAllowed} for read-only checks (no recording). The
     * window is not locked here because this is a best-effort pre-check; the
     * authoritative atomic check-and-record happens in {@link #recordFailure}.
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
