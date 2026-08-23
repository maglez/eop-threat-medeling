package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the shipped read rate limit to the value the documentation quotes (EOP-88, ADR-051).
 *
 * <p><strong>Why this is a text comparison and not a bound value.</strong> The obvious test would autowire
 * {@link ReadRateLimitProperties} and assert {@code limit() == 300}, but it cannot: {@code
 * src/test/resources/application.properties} raises the limit to {@code Integer.MAX_VALUE} for the whole suite
 * so that a shared Spring context is not exhausted part-way through, and a test cannot unset a property that a
 * profile has already set. Every bound value the suite can observe is therefore the test override, not the
 * shipped default. That is the gap this class closes instead: it reads the two files that between them decide
 * what production runs with and asserts they agree with each other and with the figure ADR-051 quotes.
 *
 * <p>It fails in both directions, which is the point. Changing {@code @DefaultValue} without changing {@code
 * application.yml} fails, and so does changing {@code application.yml} alone — the two would otherwise drift
 * silently, because the explicit value in {@code application.yml} always wins and the annotation would become
 * decoration nobody reads. It is the same technique as the documentation-integrity tests under {@code
 * src/test/java/org/maglez/eop/docs/}, and it carries the same limitation: it proves two files say the same
 * thing, never that the number is the right one.
 */
@DisplayName("the shipped read rate limit defaults")
class ReadRateLimitDefaultsTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    private static final Path PROPERTIES_SOURCE =
            Path.of("src/main/java/org/maglez/eop/config/ReadRateLimitProperties.java");

    /**
     * The {@code read-rate-limit:} block of {@code application.yml}, up to the next key at the same indentation.
     * Scoping the match to the block matters: {@code limit: 300} read against the whole file would also be
     * satisfied by some future unrelated key that happened to end in {@code limit}.
     */
    private static final Pattern READ_RATE_LIMIT_BLOCK =
            Pattern.compile("^ {4}read-rate-limit:\\R(?: {6}.*\\R|\\s*\\R|^ {6}#.*\\R)+", Pattern.MULTILINE);

    @Test
    @DisplayName("300 reads per window is the shipped limit, in both the record and the yml")
    void shouldShipThreeHundredReadsPerWindow() throws IOException {
        assertThat(Files.readString(PROPERTIES_SOURCE))
                .as("the @DefaultValue that applies when application.yml omits the key")
                .contains("@DefaultValue(\"300\")");

        assertThat(readRateLimitBlock())
                .as("the explicit value in application.yml, which always wins over the annotation")
                .contains("limit: 300");
    }

    @Test
    @DisplayName("10000 tracked keys is the shipped table size, in both the record and the yml")
    void shouldShipTenThousandTrackedKeys() throws IOException {
        assertThat(Files.readString(PROPERTIES_SOURCE))
                .as("the @DefaultValue that applies when application.yml omits the key")
                .contains("@DefaultValue(\"10000\")");

        assertThat(readRateLimitBlock())
                .as("the explicit value in application.yml, which always wins over the annotation")
                .contains("max-tracked-keys: 10000");
    }

    /**
     * Extracts the {@code eop.web.read-rate-limit} block from {@code application.yml}.
     *
     * @return the block including its own key line
     * @throws IOException if {@code application.yml} cannot be read
     */
    private static String readRateLimitBlock() throws IOException {
        final var yml = Files.readString(APPLICATION_YML);
        final var matcher = READ_RATE_LIMIT_BLOCK.matcher(yml);
        assertThat(matcher.find())
                .as("application.yml no longer declares an eop.web.read-rate-limit block at all")
                .isTrue();
        return matcher.group();
    }
}
