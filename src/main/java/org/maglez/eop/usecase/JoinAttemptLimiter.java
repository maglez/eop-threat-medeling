package org.maglez.eop.usecase;

/**
 * Port through which join attempts are rate limited.
 *
 * <p>A join code is six characters, which is roughly thirty bits. Thirty bits is
 * unguessable only while guessing is slow: unlimited attempts against a
 * six-character keyspace is an open door. This limiter is therefore a primary
 * security control rather than a courtesy about load, and removing it later would
 * be a security regression rather than a simplification (ADR-019).
 *
 * <p>Only failed attempts are counted. A facilitator sharing one code with five
 * players produces five successful joins from five addresses and must not be
 * throttled, while an attacker walking the keyspace produces nothing but misses.
 * Counting successes as well would punish the expected workflow and reward the
 * attacker with a cheaper signal.
 *
 * <p>Implementations are consulted before any lookup happens, so a refusal costs
 * no database work.
 */
public interface JoinAttemptLimiter {

    /**
     * Decides whether one more join attempt may proceed.
     *
     * @param clientAddress the address the attempt arrived from, used to throttle
     *     one caller walking many codes
     * @param joinCodeAttempt the raw code being attempted, used to throttle many
     *     callers walking one code
     * @throws TooManyJoinAttemptsException if the attempt must be refused,
     *     carrying how long the caller should wait
     */
    void checkAllowed(String clientAddress, String joinCodeAttempt);

    /**
     * Records that an attempt failed to identify a session.
     *
     * <p>Called for a code that could not be parsed and for a code that parsed but
     * matched nothing, because from the outside those two are the same answer and
     * counting them differently would make the limiter itself an oracle.
     *
     * @param clientAddress the address the attempt arrived from
     * @param joinCodeAttempt the raw code that failed
     */
    void recordFailure(String clientAddress, String joinCodeAttempt);
}
