package org.maglez.eop.entity;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The short code a facilitator reads out so other players can join.
 *
 * <p>Six characters of Crockford base32. The alphabet excludes {@code I},
 * {@code L} and {@code O} because they are misread as {@code 1} and {@code 0} on
 * a video call, and {@code U} so that a random string does not spell a word
 * somebody has to say out loud in a meeting (ADR-019).
 *
 * <p>Six characters is roughly thirty bits, which is only unguessable while
 * guessing is rate limited. The limiter on the join endpoint is therefore a
 * primary security control rather than an extra layer, and removing it later is
 * a security regression rather than a simplification.
 *
 * <p>Stored and transmitted in one canonical form: upper case, with the
 * ambiguous characters already folded away. {@link #parse(String)} does that
 * folding, so a human transcribing {@code 7qk2fm} or {@code 7QKZFM} with an
 * {@code O} for a zero still reaches the right session.
 *
 * @param value the canonical six-character code
 */
public record JoinCode(String value) {

    /** The Crockford base32 alphabet, in order. Thirty-two symbols. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Characters in a code. Changing this changes the entropy; see ADR-019. */
    public static final int LENGTH = 6;

    /**
     * Rejects a code that is not already canonical.
     *
     * <p>Strict on purpose: this constructor is for generated codes and for
     * values read back out of the database, where anything non-canonical is a
     * defect rather than a typing mistake. Human input goes through
     * {@link #parse(String)}.
     *
     * @throws NullPointerException     if the value is null
     * @throws IllegalArgumentException if the value is not exactly
     *                                  {@link #LENGTH} characters from
     *                                  {@link #ALPHABET}
     */
    public JoinCode {
        Objects.requireNonNull(value, "value is required");
        if (value.length() != LENGTH) {
            throw new IllegalArgumentException("A join code is exactly " + LENGTH + " characters, was " + value.length());
        }
        for (int index = 0; index < value.length(); index++) {
            if (ALPHABET.indexOf(value.charAt(index)) < 0) {
                throw new IllegalArgumentException("A join code contains only Crockford base32 characters");
            }
        }
    }

    /**
     * Interprets something a human typed, or reports that it cannot be a code.
     *
     * <p>Returns an empty optional rather than throwing, because the caller's
     * response to "that is not a code" and to "no session has that code" must be
     * identical. Distinguishing them would turn the join endpoint into an oracle
     * that confirms which codes are real, and at thirty bits of entropy that is
     * exactly the help an attacker needs.
     *
     * @param raw whatever arrived on the request, possibly null
     * @return the canonical code, or empty if the input cannot be one
     */
    public static Optional<JoinCode> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String folded = raw.strip()
                .toUpperCase(Locale.ROOT)
                .replace('I', '1')
                .replace('L', '1')
                .replace('O', '0');
        if (folded.length() != LENGTH) {
            return Optional.empty();
        }
        for (int index = 0; index < folded.length(); index++) {
            if (ALPHABET.indexOf(folded.charAt(index)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(new JoinCode(folded));
    }
}
