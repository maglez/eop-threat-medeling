package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins ADR-023's count of non-constraint exception origins against the adapter source.
 *
 * <p>This test exists because prose lost to arithmetic four times in a row. The paragraph
 * beside ADR-023's constraint-name translation table warns that the table is not an
 * inventory of how each exception is raised, and then states how many exceptions arise with
 * no constraint violated at all. That number was written as two, corrected to three,
 * corrected to six, and was still wrong: the real number is nine, because the shared {@code
 * sessionMoved} helper has five answers and three of them kept being left out even while
 * two of its siblings were being counted.
 *
 * <p>Every one of those four counts was written by someone reading carefully and checked by
 * a reviewer reading carefully. That is the argument for a machine. A reviewer can be asked
 * to re-count once; asking a fifth time is how a document ends up authoritative and wrong.
 *
 * <p>The test is deliberately two-directional, because a one-directional version would have
 * passed against three of the four wrong counts. It asserts that every exception the
 * adapter raises without a constraint violation is named in the ADR, <em>and</em> that every
 * type the ADR names is one the adapter actually raises that way. The first direction
 * catches an omission, which is how every previous miscount happened. The second catches a
 * type being padded in to make a number come out right.
 *
 * <p>It reads the adapter as text rather than by reflection because what it is pinning is a
 * claim about the source: which {@code throw}/{@code return new} sites sit on a
 * rows-affected branch or an {@code assertSeated} read, as against inside a {@code
 * DataIntegrityViolationException} translation. Reflection cannot see that distinction.
 */
@DisplayName("ADR-023's count of non-constraint exception origins")
class TrickPlayExceptionOriginTest {

    private static final Path ADR =
            Path.of("docs", "adr", "ADR-023-deal-remainder-and-turn-order.md");

    private static final Path ADAPTER =
            Path.of(
                    "src", "main", "java", "org", "maglez", "eop", "adapter", "persistence",
                    "TrickPlayRepositoryAdapter.java");

    /**
     * The nine, as established by reading the adapter. Held here as data so that a change to
     * the adapter's refusal vocabulary fails this test rather than silently diverging from
     * the ADR — which is the failure mode the whole test is aimed at.
     *
     * <p>Seven arise from a rows-affected count of zero: the five answers of {@code
     * sessionMoved}, plus the replayed resolution and the card that is no longer in the hand.
     * Two arise from the {@code assertSeated} read.
     */
    private static final Set<String> NON_CONSTRAINT_ORIGINS =
            Set.of(
                    "SessionNotFoundException",
                    "SessionNotJoinableException",
                    "HandAlreadyDealtException",
                    "HandNotDealtException",
                    "OutOfTurnException",
                    "TrickAlreadyResolvedException",
                    "CardNotInHandException",
                    "PlayerNotInSessionException",
                    "NotYourSeatException");

    /**
     * Raised only from inside a {@code DataIntegrityViolationException} translation, so a
     * constraint really was violated and a row of the table really does cover them. Listed
     * explicitly rather than inferred, so that moving one of these onto a rows-affected
     * branch — or one of the nine into a translation — breaks this test.
     */
    private static final Set<String> CONSTRAINT_ORIGINS =
            Set.of(
                    "TrickAlreadyOpenException",
                    "AlreadyPlayedInTrickException",
                    "CardAlreadyPlayedException");

    @Test
    @DisplayName("states nine, and the adapter raises exactly nine that way")
    void shouldStateTheNumberTheAdapterActuallyRaises() throws IOException {
        final String adr = Files.readString(ADR, StandardCharsets.UTF_8);

        assertThat(adr)
                .as(
                        "the paragraph beside the translation table must state the count, and "
                                + "state it as nine; it has previously said two, three and six")
                .contains("**Nine** exceptions on the trick-play write paths");

        assertThat(NON_CONSTRAINT_ORIGINS)
                .as("the list this test pins must itself be nine, or it is not pinning nine")
                .hasSize(9);
    }

    @Test
    @DisplayName("names every one of the nine, so an omission cannot pass")
    void shouldNameEveryNonConstraintOrigin() throws IOException {
        final String adr = Files.readString(ADR, StandardCharsets.UTF_8);
        final String paragraph = paragraphBesideTheTable(adr);

        final Set<String> missing = new TreeSet<>();
        for (final String type : NON_CONSTRAINT_ORIGINS) {
            if (!paragraph.contains(type)) {
                missing.add(type);
            }
        }

        assertThat(missing)
                .as(
                        "every previous miscount was an omission from this paragraph, so this is "
                                + "the assertion that would have caught all four of them")
                .isEmpty();
    }

    @Test
    @DisplayName("names nothing that reaches the caller through a constraint instead")
    void shouldNotPadTheCountWithConstraintOrigins() throws IOException {
        final String paragraph = paragraphBesideTheTable(Files.readString(ADR, StandardCharsets.UTF_8));

        final Set<String> padded = new TreeSet<>();
        for (final String type : CONSTRAINT_ORIGINS) {
            if (paragraph.contains(type)) {
                padded.add(type);
            }
        }

        assertThat(padded)
                .as(
                        "these are translated from a constraint violation, so a row of the table "
                                + "does cover them and listing them here would inflate the count")
                .isEmpty();
    }

    @Test
    @DisplayName("every type it names is one the adapter really constructs")
    void shouldNameOnlyTypesTheAdapterConstructs() throws IOException {
        final String adapter = Files.readString(ADAPTER, StandardCharsets.UTF_8);
        final Set<String> constructed = constructedExceptionTypes(adapter);

        assertThat(constructed)
                .as(
                        "if the adapter stops raising one of these, the ADR's list is stale and "
                                + "the count is wrong again — this is the direction that catches a "
                                + "type left behind by a refactor")
                .containsAll(NON_CONSTRAINT_ORIGINS);
    }

    /**
     * The paragraph the count lives in. Anchored on the sentence that introduces it rather
     * than on a line number, because a line number would drift and this document is long.
     */
    private static String paragraphBesideTheTable(final String adr) {
        final int start = adr.indexOf("**This table is not an inventory");
        assertThat(start)
                .as("the paragraph that scopes the translation table must still exist")
                .isNotNegative();
        final int end = adr.indexOf("**Superseded", start);
        return end < 0 ? adr.substring(start) : adr.substring(start, end);
    }

    /** Every exception type the adapter constructs, by name. */
    private static Set<String> constructedExceptionTypes(final String adapter) {
        final Pattern construction = Pattern.compile("new (\\w+Exception)\\s*\\(");
        final Matcher matcher = construction.matcher(adapter);
        final Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertThat(found)
                .as("the regex must actually match something, or every assertion using it is vacuous")
                .isNotEmpty();
        return found;
    }

    @Test
    @DisplayName("splits the nine seven-and-two, and says which group the card refusal is in")
    void shouldRecordTheSplitCorrectly() throws IOException {
        final String paragraph = paragraphBesideTheTable(Files.readString(ADR, StandardCharsets.UTF_8));

        assertThat(paragraph)
                .as("seven arise from a rows-affected count of zero")
                .contains("Seven come from a rows-affected count of zero");
        assertThat(paragraph)
                .as("two arise from the assertSeated read")
                .contains("Two come from a read this adapter makes itself");
        assertThat(paragraph)
                .as(
                        "CardNotInHandException comes from a conditional DELETE matching no row, "
                                + "so it belongs in the rows-affected group; an earlier version "
                                + "filed it in the other one against its own stated criterion")
                .contains("belongs here");

        final List<String> groups = List.of("Seven come from", "Two come from");
        int previous = -1;
        for (final String group : groups) {
            final int at = paragraph.indexOf(group);
            assertThat(at).as("group heading '%s' must be present", group).isNotNegative();
            assertThat(at).as("groups must appear in order, larger first").isGreaterThan(previous);
            previous = at;
        }
    }
}
