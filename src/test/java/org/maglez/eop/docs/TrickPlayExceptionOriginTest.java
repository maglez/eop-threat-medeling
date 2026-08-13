package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Derives the set of non-constraint exception origins from the adapter and holds ADR-023 to it.
 *
 * <p>This test exists because prose lost to arithmetic three times running. The paragraph
 * beside ADR-023's constraint-name translation table warns that the table is not an inventory
 * of how each exception is raised, and then states how many exceptions arise with no
 * constraint violated at all. It said two, then three, then six. The answer is nine, because
 * the shared {@code sessionMoved} helper has five answers and three of them kept being left
 * out even while two of its siblings were being counted. Nine is the fourth statement of the
 * count and the first correct one.
 *
 * <p>Every one of those three wrong counts was written by someone reading carefully and passed
 * by a reviewer reading carefully. That is the argument for a machine.
 *
 * <p><strong>The first version of this test was not that machine, and three review gates said
 * so.</strong> It compared the ADR against a hard-coded list, and its own Javadoc claimed to
 * derive the set from the adapter's branches. It did not: its regex collected every exception
 * construction anywhere in the file, so a tenth origin appearing in the adapter would have left
 * the suite green while the ADR silently went stale — which is the more likely future event,
 * since the adapter is the code under development. It also claimed to be two-directional when
 * both of its assertions ran the same way, so padding the paragraph with an invented type
 * passed. A test that oversells itself is worse than no test, because it stops people checking.
 *
 * <p>So this version derives the set. It classifies every construction of a type the adapter
 * imports from {@code org.maglez.eop.entity} by <em>where</em> it sits: inside {@code
 * dealFailure}, {@code playFailure} or the {@code openTrick} catch block, a constraint really
 * was violated and a row of the table covers it; anywhere else, the refusal came from a
 * rows-affected count of zero or from the {@code assertSeated} read, and no row can. The ADR's
 * named list must then equal the derived set — <em>equal</em>, not contain, so that a tenth
 * origin in the adapter and a fabricated tenth name in the prose both fail.
 *
 * <p>It reads the adapter as text because the distinction being derived is positional and
 * reflection cannot see it. That is a real limitation and worth naming: a construction moved
 * into a helper called <em>from</em> a translation would be classified wrongly. What the text
 * approach does buy is the one property that matters here — the set is computed from the source
 * rather than restated beside it.
 */
@DisplayName("ADR-023's non-constraint exception origins, derived from the adapter")
class TrickPlayExceptionOriginTest {

    private static final Path ADR =
            Path.of("docs", "adr", "ADR-023-deal-remainder-and-turn-order.md");

    private static final Path ADAPTER =
            Path.of(
                    "src", "main", "java", "org", "maglez", "eop", "adapter", "persistence",
                    "TrickPlayRepositoryAdapter.java");

    /**
     * The count as it stands. Asserted against the derived set rather than trusted, so this
     * constant cannot drift from the code the way the prose did — if the adapter grows a tenth
     * origin, the derivation returns ten and this fails.
     */
    private static final int EXPECTED_ORIGIN_COUNT = 9;

    private static String adapterSource() throws IOException {
        return Files.readString(ADAPTER, StandardCharsets.UTF_8);
    }

    private static String adrSource() throws IOException {
        return Files.readString(ADR, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("derives exactly nine of them from the adapter source")
    void shouldDeriveNineOriginsFromTheAdapter() throws IOException {
        final Set<String> derived = nonConstraintOrigins(adapterSource());

        assertThat(derived)
                .as(
                        "the adapter's non-constraint refusal vocabulary, derived by position. If "
                                + "this is not nine the ADR is stale and so is the constant above")
                .hasSize(EXPECTED_ORIGIN_COUNT);
    }

    @Test
    @DisplayName("states the derived number, in words, in the paragraph that scopes the table")
    void shouldStateTheDerivedNumber() throws IOException {
        final int derived = nonConstraintOrigins(adapterSource()).size();
        final String paragraph = paragraphBesideTheTable(adrSource());

        assertThat(paragraph)
                .as(
                        "the paragraph must state the count the adapter actually produces; it has "
                                + "previously said two, three and six")
                .contains("**" + numberWord(derived) + "** exceptions on the trick-play write paths");
    }

    /**
     * The assertion the previous version should have made. Set equality in both directions: an
     * origin the adapter raises but the ADR omits fails, and a name the ADR invents but the
     * adapter never raises that way also fails. Each of the three historical miscounts was an
     * omission, so the first direction would have caught all three.
     */
    @Test
    @DisplayName("names exactly the derived set — no omission, no padding")
    void shouldNameExactlyTheDerivedSet() throws IOException {
        final Set<String> derived = nonConstraintOrigins(adapterSource());
        final String paragraph = paragraphBesideTheTable(adrSource());

        final Set<String> omitted = new TreeSet<>();
        for (final String type : derived) {
            if (!paragraph.contains(type)) {
                omitted.add(type);
            }
        }

        final Set<String> padded = new TreeSet<>();
        for (final String type : constraintTranslatedOrigins(adapterSource())) {
            if (paragraph.contains(type) && !derived.contains(type)) {
                padded.add(type);
            }
        }

        assertThat(omitted)
                .as("every miscount so far was an omission from this paragraph")
                .isEmpty();
        assertThat(padded)
                .as(
                        "these reach the caller through a constraint, so a row of the table covers "
                                + "them and naming them here would inflate the count")
                .isEmpty();
    }

    @Test
    @DisplayName("splits them by mechanism, and the two groups sum to the derived total")
    void shouldRecordTheSplit() throws IOException {
        final String paragraph = paragraphBesideTheTable(adrSource());
        final int derived = nonConstraintOrigins(adapterSource()).size();
        final int fromReads = seatReadOrigins(adapterSource()).size();
        final int fromRowCounts = derived - fromReads;

        assertThat(paragraph)
                .as("the larger group is the rows-affected one")
                .contains(numberWord(fromRowCounts) + " come from a rows-affected count of zero");
        assertThat(paragraph)
                .as("the smaller group is the assertSeated read")
                .contains(numberWord(fromReads) + " come from a read this adapter makes itself");

        assertThat(fromRowCounts + fromReads)
                .as("the split must account for every derived origin and invent none")
                .isEqualTo(derived);
    }

    /**
     * Every domain exception the adapter constructs outside a constraint translation. These are
     * the ones no row of ADR-023's table can cover, because no constraint was violated.
     */
    private static Set<String> nonConstraintOrigins(final String adapter) {
        final Set<String> all = domainExceptionsConstructedIn(adapter, wholeFileExcludingTranslations(adapter));
        assertThat(all)
                .as("the derivation must find something, or every assertion using it is vacuous")
                .isNotEmpty();
        return all;
    }

    /** Every domain exception constructed inside a constraint translation. */
    private static Set<String> constraintTranslatedOrigins(final String adapter) {
        final Set<String> found = new TreeSet<>();
        for (final int[] region : translationRegions(adapter)) {
            found.addAll(domainExceptionsConstructedIn(adapter, Set.of(region)));
        }
        assertThat(found)
                .as("the translation regions must contain constructions, or the split is meaningless")
                .isNotEmpty();
        return found;
    }

    /** The two raised by the {@code assertSeated} read rather than by a row count. */
    private static Set<String> seatReadOrigins(final String adapter) {
        final int[] region = bodyOf(adapter, adapter.indexOf("private Map<UUID, Integer> seatsByPlayer"));
        final int assertSeated = adapter.indexOf("private void assertSeated");
        final int[] seated = bodyOf(adapter, assertSeated);
        final Set<String> found = domainExceptionsConstructedIn(adapter, Set.of(seated));
        assertThat(found)
                .as("assertSeated must still be the source of the read-based refusals")
                .isNotEmpty();
        assertThat(region[0]).as("seatsByPlayer must still exist beside it").isNotNegative();
        return found;
    }

    /**
     * The byte ranges of the three places a {@code DataIntegrityViolationException} is turned
     * into a domain exception: the two failure helpers and the inline catch in {@code openTrick}.
     */
    private static Set<int[]> translationRegions(final String adapter) {
        final Set<int[]> regions = new java.util.LinkedHashSet<>();
        regions.add(bodyOf(adapter, adapter.indexOf("private RuntimeException dealFailure")));
        regions.add(bodyOf(adapter, adapter.indexOf("private RuntimeException playFailure")));

        final String catchAnchor = "catch (final DataIntegrityViolationException";
        int catchAt = adapter.indexOf(catchAnchor);
        assertThat(catchAt)
                .as("the adapter must still catch the violation somewhere, or there is no "
                        + "translation to exclude and this whole derivation is meaningless")
                .isNotNegative();
        int caught = 0;
        while (catchAt >= 0) {
            regions.add(bodyOf(adapter, catchAt));
            caught++;
            catchAt = adapter.indexOf(catchAnchor, catchAt + catchAnchor.length());
        }
        assertThat(caught)
                .as("every catch of the violation is a translation site: two in recordDeal "
                        + "delegating to dealFailure, one inline in openTrick, one in appendPlay "
                        + "delegating to playFailure. A new one appearing here must be classified "
                        + "deliberately rather than silently counted as a non-constraint origin")
                .isEqualTo(4);
        return regions;
    }

    private static Set<int[]> wholeFileExcludingTranslations(final String adapter) {
        final Set<int[]> excluded = translationRegions(adapter);
        final Set<int[]> keep = new java.util.LinkedHashSet<>();
        int cursor = 0;
        for (final int[] region : sortedByStart(excluded)) {
            keep.add(new int[] {cursor, region[0]});
            cursor = region[1];
        }
        keep.add(new int[] {cursor, adapter.length()});
        return keep;
    }

    private static java.util.List<int[]> sortedByStart(final Set<int[]> regions) {
        final java.util.List<int[]> sorted = new java.util.ArrayList<>(regions);
        sorted.sort(java.util.Comparator.comparingInt(region -> region[0]));
        return sorted;
    }

    /** The body of the declaration starting at {@code from}, as a half-open byte range. */
    private static int[] bodyOf(final String source, final int from) {
        assertThat(from).as("the declaration this test anchors on must still exist").isNotNegative();
        final int open = source.indexOf('{', from);
        assertThat(open).as("the declaration must have a body").isNotNegative();
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            final char character = source.charAt(at);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return new int[] {open, at + 1};
                }
            }
        }
        throw new AssertionError("unbalanced braces from offset " + from);
    }

    /**
     * Domain exception constructions within the given ranges. "Domain" means a type the adapter
     * imports from {@code org.maglez.eop.entity}, which excludes {@code IllegalStateException}
     * and {@code IllegalArgumentException} — server faults rather than refusals a caller earns.
     */
    private static Set<String> domainExceptionsConstructedIn(
            final String adapter, final Set<int[]> ranges) {
        final Set<String> domainTypes = importedDomainExceptions(adapter);
        final Pattern construction = Pattern.compile("new (\\w+Exception)\\s*\\(");
        final Set<String> found = new TreeSet<>();
        for (final int[] range : ranges) {
            final Matcher matcher = construction.matcher(adapter.substring(range[0], range[1]));
            while (matcher.find()) {
                if (domainTypes.contains(matcher.group(1))) {
                    found.add(matcher.group(1));
                }
            }
        }
        return found;
    }

    /** Exception types the adapter imports from the entity package. */
    private static Set<String> importedDomainExceptions(final String adapter) {
        final Pattern imported =
                Pattern.compile("import org\\.maglez\\.eop\\.entity\\.(\\w+Exception);");
        final Matcher matcher = imported.matcher(adapter);
        final Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertThat(found)
                .as("the adapter must still import its domain exceptions by name")
                .isNotEmpty();
        return found;
    }

    /** The paragraph the count lives in, anchored on text rather than a line number. */
    private static String paragraphBesideTheTable(final String adr) {
        final int start = adr.indexOf("**This table is not an inventory");
        assertThat(start)
                .as("the paragraph that scopes the translation table must still exist")
                .isNotNegative();
        final int end = adr.indexOf("**Superseded", start);
        assertThat(end)
                .as(
                        "the superseded note must still follow it, or this extraction silently "
                                + "widens to the rest of the document and the padding check goes "
                                + "vacuous")
                .isGreaterThan(start);
        return adr.substring(start, end);
    }

    private static String numberWord(final int value) {
        return switch (value) {
            case 2 -> "Two";
            case 3 -> "Three";
            case 4 -> "Four";
            case 5 -> "Five";
            case 6 -> "Six";
            case 7 -> "Seven";
            case 8 -> "Eight";
            case 9 -> "Nine";
            case 10 -> "Ten";
            default -> String.valueOf(value);
        };
    }
}
