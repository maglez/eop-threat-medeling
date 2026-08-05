package org.maglez.eop.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JoinCode")
class JoinCodeTest {

    private static final String CANONICAL = "7QK2FM";

    @Nested
    @DisplayName("the strict constructor, for generated and stored codes")
    class StrictConstruction {

        @Test
        @DisplayName("accepts a canonical code")
        void shouldAcceptCanonicalCode() {
            assertThat(new JoinCode(CANONICAL).value()).isEqualTo(CANONICAL);
        }

        @Test
        @DisplayName("rejects null")
        void shouldRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JoinCode(null))
                    .withMessageContaining("value");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "7QK2F", "7QK2FMM"})
        @DisplayName("rejects anything that is not exactly six characters")
        void shouldRejectWrongLength(final String candidate) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JoinCode(candidate))
                    .withMessageContaining("exactly 6 characters");
        }

        @ParameterizedTest
        @ValueSource(strings = {"7QK2FI", "7QK2FL", "7QK2FO", "7QK2FU"})
        @DisplayName("rejects each ambiguous character the alphabet leaves out")
        void shouldRejectExcludedCharacters(final String candidate) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JoinCode(candidate))
                    .withMessageContaining("Crockford base32");
        }

        @Test
        @DisplayName("rejects lower case, because the canonical form is upper case")
        void shouldRejectLowerCase() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JoinCode(CANONICAL.toLowerCase(java.util.Locale.ROOT)))
                    .withMessageContaining("Crockford base32");
        }

        @Test
        @DisplayName("the alphabet is thirty-two symbols and omits I, L, O and U")
        void shouldExcludeMisreadCharactersFromAlphabet() {
            assertThat(JoinCode.ALPHABET).hasSize(32).doesNotContain("I", "L", "O", "U");
            assertThat(JoinCode.LENGTH).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("parsing what a human typed")
    class Parsing {

        @Test
        @DisplayName("upper cases and strips surrounding whitespace")
        void shouldFoldCaseAndWhitespace() {
            assertThat(JoinCode.parse("  7qk2fm  ")).contains(new JoinCode(CANONICAL));
        }

        @Test
        @DisplayName("folds I and L to one, and O to zero, as a video call mangles them")
        void shouldFoldMisreadCharacters() {
            assertThat(JoinCode.parse("IL0K2F")).contains(new JoinCode("110K2F"));
            assertThat(JoinCode.parse("7QKOFM")).contains(new JoinCode("7QK0FM"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "7QK2F", "7QK2FMM", "7QK2F!", "7QK2FU"})
        @DisplayName("returns empty rather than throwing, for every unusable input")
        void shouldReturnEmptyForUnusableInput(final String candidate) {
            assertThat(JoinCode.parse(candidate)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null")
        void shouldReturnEmptyForNull() {
            assertThat(JoinCode.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("U is not folded, so a code containing one is simply not a code")
        void shouldNotFoldU() {
            assertThat(JoinCode.parse("7QK2FU")).isEmpty();
            assertThat(JoinCode.parse("7qk2fu")).isEmpty();
        }
    }
}
