package org.maglez.eop.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The SHA-256 digest of a player's identity token, hex encoded.
 *
 * <p>The token itself is 256 bits of {@code SecureRandom} output, handed to the
 * client once and never stored (ADR-015). Whoever holds it is that player, so
 * keeping it in the clear would be the same class of mistake as storing a
 * plaintext password.
 *
 * <p>A plain digest rather than a password KDF is the right choice here, and for
 * a specific reason: a KDF exists to make guessing a low-entropy human secret
 * expensive. The input here is not low-entropy, so there is nothing to slow down
 * — an attacker who could brute-force 256 random bits would not be inconvenienced
 * by an iteration count.
 *
 * <p>Comparison happens as an indexed database lookup rather than in Java, so
 * there is no meaningful timing side channel in this type. {@link #toString()}
 * is redacted anyway: a digest is not a credential, but log output has a way of
 * ending up in places nobody audited.
 *
 * <p>Uses {@link MessageDigest} from the JDK, not a framework hashing utility,
 * because this class lives in the domain and the domain imports nothing.
 *
 * @param value the digest as sixty-four lower-case hex characters
 */
public record IdentityTokenHash(String value) {

    /** Hex characters in a SHA-256 digest, and the column width. */
    public static final int HEX_LENGTH = 64;

    private static final String ALGORITHM = "SHA-256";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Rejects anything that is not a SHA-256 digest in hex.
     *
     * @throws NullPointerException     if the value is null
     * @throws IllegalArgumentException if the value is not sixty-four
     *                                  lower-case hex characters
     */
    public IdentityTokenHash {
        Objects.requireNonNull(value, "value is required");
        if (value.length() != HEX_LENGTH) {
            throw new IllegalArgumentException("A token hash is exactly " + HEX_LENGTH + " hex characters, was " + value.length());
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final boolean isHex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
            if (!isHex) {
                throw new IllegalArgumentException("A token hash contains only lower-case hex characters");
            }
        }
    }

    /**
     * Digests a plaintext identity token.
     *
     * @param plaintextToken the token as issued to the client
     * @return the digest to store and to look up by
     */
    public static IdentityTokenHash of(final String plaintextToken) {
        Objects.requireNonNull(plaintextToken, "plaintextToken is required");
        final byte[] digest = digest(plaintextToken.getBytes(StandardCharsets.UTF_8));
        final char[] hex = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            final int unsigned = digest[index] & 0xFF;
            hex[index * 2] = HEX_DIGITS[unsigned >>> 4];
            hex[index * 2 + 1] = HEX_DIGITS[unsigned & 0x0F];
        }
        return new IdentityTokenHash(new String(hex));
    }

    private static byte[] digest(final byte[] input) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(input);
        }
        catch (final NoSuchAlgorithmException impossible) {
            // Every conforming Java runtime is required to provide SHA-256.
            throw new IllegalStateException(ALGORITHM + " is unavailable on this runtime", impossible);
        }
    }

    /**
     * A redacted description, safe to log.
     *
     * @return a constant marker carrying no part of the digest
     */
    @Override
    public String toString() {
        return "IdentityTokenHash[redacted]";
    }
}
