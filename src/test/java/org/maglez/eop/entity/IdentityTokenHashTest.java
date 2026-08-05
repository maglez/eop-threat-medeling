package org.maglez.eop.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentityTokenHash}.
 *
 * <p>The hashing is asserted against a published SHA-256 vector rather than
 * against a second implementation, so a change of algorithm fails the build
 * instead of quietly invalidating every stored digest.
 */
@DisplayName("IdentityTokenHash")
class IdentityTokenHashTest {

    /** SHA-256 of the three bytes {@code abc}, from FIPS 180-4. */
    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Nested
    @DisplayName("hashing a plaintext token")
    class Hashing {

        @Test
        @DisplayName("produces the published SHA-256 vector in lower-case hex")
        void shouldMatchPublishedVector() {
            assertThat(IdentityTokenHash.of("abc").value()).isEqualTo(SHA256_OF_ABC);
        }

        @Test
        @DisplayName("produces the same digest for the same token")
        void shouldBeDeterministic() {
            assertThat(IdentityTokenHash.of("a-token")).isEqualTo(IdentityTokenHash.of("a-token"));
        }

        @Test
        @DisplayName("produces different digests for tokens differing by one character")
        void shouldDifferForDifferentTokens() {
            assertThat(IdentityTokenHash.of("a-token")).isNotEqualTo(IdentityTokenHash.of("a-tokeN"));
        }

        @Test
        @DisplayName("rejects a null token rather than hashing the string \"null\"")
        void shouldRejectNullToken() {
            assertThatNullPointerException().isThrownBy(() -> IdentityTokenHash.of(null));
        }

        @Test
        @DisplayName("always produces sixty-four hex characters")
        void shouldAlwaysProduceFixedLength() {
            assertThat(IdentityTokenHash.of("x").value()).hasSize(IdentityTokenHash.HEX_LENGTH);
        }
    }

    @Nested
    @DisplayName("the strict constructor, used when reading a stored digest")
    class StrictConstruction {

        @Test
        @DisplayName("accepts a canonical digest")
        void shouldAcceptCanonicalDigest() {
            assertThat(new IdentityTokenHash(SHA256_OF_ABC).value()).isEqualTo(SHA256_OF_ABC);
        }

        @Test
        @DisplayName("rejects null")
        void shouldRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> new IdentityTokenHash(null));
        }

        @Test
        @DisplayName("rejects a digest of the wrong length")
        void shouldRejectWrongLength() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentityTokenHash(SHA256_OF_ABC.substring(1)))
                    .withMessageContaining("exactly 64 hex characters");
        }

        @Test
        @DisplayName("rejects upper-case hex, so one token cannot yield two stored forms")
        void shouldRejectUpperCaseHex() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentityTokenHash(SHA256_OF_ABC.toUpperCase(java.util.Locale.ROOT)))
                    .withMessageContaining("lower-case hex");
        }

        @Test
        @DisplayName("rejects a non-hex character")
        void shouldRejectNonHexCharacter() {
            final String withZed = "z" + SHA256_OF_ABC.substring(1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentityTokenHash(withZed))
                    .withMessageContaining("lower-case hex");
        }
    }

    @Test
    @DisplayName("never prints the digest, so a log line cannot become a credential")
    void shouldRedactItselfInToString() {
        final IdentityTokenHash hash = IdentityTokenHash.of("abc");

        assertThat(hash).hasToString("IdentityTokenHash[redacted]");
        assertThat(hash.toString()).doesNotContain(SHA256_OF_ABC);
    }
}
