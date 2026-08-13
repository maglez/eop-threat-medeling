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
 * imports from {@code org.maglez.eop.entity} by <em>where</em> it sits. Inside {@code
 * dealFailure}, {@code playFailure}, or any of the four {@code catch (final
 * DataIntegrityViolationException ...)} blocks — two in {@code recordDeal}, one inline in {@code
 * openTrick}, one in {@code appendPlay} — a constraint really was violated and a row of the table
 * covers it. Inside {@code assertSeated}, the refusal came from a read. Anywhere else it came
 * from a rows-affected count of zero. Both groups are located by cutting regions out of the
 * source, not by subtracting one group's names from the other's. Filing a member by arithmetic is
 * exactly the mistake the six-count made with {@code CardNotInHandException}, and an earlier
 * version of this class reintroduced it one layer down — see {@link #rowCountOrigins}.
 *
 * <p>The ADR's named list must then <em>equal</em> the derived set, not merely contain it. The
 * names are read from the backticked tokens of the two blocks that enumerate the groups, so a
 * tenth origin the adapter gains and a tenth name the prose invents both fail. An earlier version
 * claimed that while checking padding against a closed list of four types, which let a fabricated
 * name straight through; a review gate proved it with a mutation, and the mutation now fails.
 *
 * <p>One limitation, measured rather than assumed. Membership is detected by a name appearing in a
 * group block, so if a block mentions the same type twice — once enumerating it, once in narrative
 * — deleting only the enumerating mention leaves it detected and the omission passes. That is why
 * the mutation validating this class removes <em>every</em> mention: with the name genuinely
 * absent, which is the shape all three historical miscounts actually had, the omission check fails
 * as it should. A block would have to keep discussing a type it had stopped listing for this to
 * hide anything.
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

        final Set<String> padded = new TreeSet<>(namedAsMembers(paragraph));
        padded.removeAll(derived);

        assertThat(omitted)
                .as("every miscount so far was an omission from this paragraph")
                .isEmpty();
        assertThat(padded)
                .as(
                        "every exception name the paragraph mentions must be one of the derived "
                                + "origins. A name here that the adapter never raises outside a "
                                + "constraint translation inflates the count — whether it is one of "
                                + "the constraint-translated types, which a row of the table already "
                                + "covers, or a type that does not exist at all")
                .isEmpty();
        assertThat(namedAsMembers(paragraph))
                .as("stated as equality, so neither direction can drift unnoticed")
                .isEqualTo(derived);
    }

    @Test
    @DisplayName("splits them by mechanism, both groups located positionally")
    void shouldRecordTheSplit() throws IOException {
        final String adapter = adapterSource();
        final String paragraph = paragraphBesideTheTable(adrSource());
        final Set<String> fromReads = seatReadOrigins(adapter);
        final Set<String> fromRowCounts = rowCountOrigins(adapter);

        assertThat(paragraph)
                .as("the larger group is the rows-affected one")
                .contains(numberWord(fromRowCounts.size()) + " are raised from a rows-affected count of zero");
        assertThat(paragraph)
                .as("the smaller group is the assertSeated read")
                .contains(numberWord(fromReads.size()) + " are raised from a read this adapter makes itself");

        final Set<String> union = new TreeSet<>(fromRowCounts);
        union.addAll(fromReads);
        assertThat(union)
                .as(
                        "the two positionally-derived groups must together be exactly the derived "
                                + "set, with no member in both and none unaccounted for")
                .isEqualTo(nonConstraintOrigins(adapter));
        assertThat(fromRowCounts)
                .as("no origin may be counted in both groups")
                .doesNotContainAnyElementsOf(fromReads);
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

    /**
     * Every domain exception the adapter constructs inside a constraint translation. Not used to
     * derive anything — kept because it is the complement of {@link #nonConstraintOrigins} and
     * asserting it is non-empty proves the region subtraction actually removed something.
     */
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

    /**
     * The refusals raised from the {@code assertSeated} read rather than from a row count. Located
     * by that method's own body, not by subtraction.
     */
    private static Set<String> seatReadOrigins(final String adapter) {
        final int[] seated = bodyOf(adapter, adapter.indexOf("private void assertSeated"));
        final Set<String> found = domainExceptionsConstructedIn(adapter, Set.of(seated));
        assertThat(found)
                .as("assertSeated must still be the source of the read-based refusals")
                .isNotEmpty();
        return found;
    }

    /**
     * The refusals raised from a rows-affected count of zero: constructions in the source that is
     * left after cutting out both the constraint translations and {@code assertSeated}'s body.
     *
     * <p>This is a <em>region</em> complement, not a name-level subtraction, and the difference is
     * not academic. An earlier version computed {@link #nonConstraintOrigins} and then
     * {@code removeAll}-ed the names found in {@code assertSeated} — which unconditionally removed
     * a type from this group whenever it appeared in that method, even if it was <em>also</em>
     * constructed at a rows-affected position. That made the union and disjointness assertions in
     * {@code shouldRecordTheSplit} tautologies, and a review gate proved it by adding a second
     * construction of {@code PlayerNotInSessionException} to {@code sessionMoved}: the derivation
     * still reported seven, still reported no overlap, and the build stayed green while the ADR's
     * split had become false. Cutting regions rather than names means that mutation now surfaces as
     * an overlap and fails.
     */
    private static Set<String> rowCountOrigins(final String adapter) {
        final Set<String> found =
                domainExceptionsConstructedIn(adapter, rowCountRegions(adapter));
        assertThat(found)
                .as("the rows-affected group must not be empty, or the derivation is inverted")
                .isNotEmpty();
        return found;
    }

    /** Everything that is neither a constraint translation nor the {@code assertSeated} read. */
    private static Set<int[]> rowCountRegions(final String adapter) {
        return excluding(
                wholeFileExcludingTranslations(adapter),
                bodyOf(adapter, adapter.indexOf("private void assertSeated")));
    }

    /**
     * The given ranges with {@code hole} cut out of each, splitting any range that straddles it.
     * Ranges are half-open, so a range touching the hole's boundary is preserved intact.
     */
    private static Set<int[]> excluding(final Set<int[]> ranges, final int[] hole) {
        final Set<int[]> kept = new java.util.LinkedHashSet<>();
        for (final int[] range : ranges) {
            if (hole[1] <= range[0] || hole[0] >= range[1]) {
                kept.add(range);
                continue;
            }
            if (range[0] < hole[0]) {
                kept.add(new int[] {range[0], hole[0]});
            }
            if (hole[1] < range[1]) {
                kept.add(new int[] {hole[1], range[1]});
            }
        }
        assertThat(kept)
                .as("cutting the read region must not consume the whole file")
                .isNotEmpty();
        return kept;
    }

    /**
     * Every exception type the paragraph presents <em>as a member</em> of the nine, read from the
     * backticked tokens of the two group blocks and nowhere else.
     *
     * <p>Scoping this to the group blocks rather than the whole paragraph is not a loophole, it is
     * the correction of one. The paragraph legitimately names other types for contrast — the
     * universe sentence names {@code IllegalStateException} and {@code IllegalArgumentException}
     * precisely in order to exclude them — so a whole-paragraph sweep flagged those two as padding.
     * That was the check working, and it showed "names exactly the derived set" was loose about
     * where a name has to appear. Membership is claimed in the two blocks that enumerate the groups,
     * so that is where equality is enforced.
     */
    private static Set<String> namedAsMembers(final String paragraph) {
        final Set<String> found = new TreeSet<>();
        for (final String block : paragraph.split("\\n\\s*\\n")) {
            final String trimmed = block.trim();
            if (!trimmed.startsWith("**Seven are raised from") && !trimmed.startsWith("**Two are raised from")) {
                continue;
            }
            final Matcher matcher = Pattern.compile("`(\\w+Exception)`").matcher(trimmed);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        assertThat(found)
                .as(
                        "no block starting '**Seven are raised from' or '**Two are raised from' "
                                + "named any exception in backticks. Those two prefixes are "
                                + "load-bearing: rewording either heading lands here, and without "
                                + "them the equality check below is vacuous")
                .isNotEmpty();
        return found;
    }

    /**
     * The byte ranges of every place a {@code DataIntegrityViolationException} is turned into a
     * domain exception, or handed to something that does: the two failure helpers, and all four
     * catch blocks — two in {@code recordDeal} and one in {@code appendPlay} that delegate, and
     * one inline in {@code openTrick} that translates in place.
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

    /** The file with the translation regions cut out of it. */
    private static Set<int[]> wholeFileExcludingTranslations(final String adapter) {
        final Set<int[]> keep = new java.util.LinkedHashSet<>();
        int cursor = 0;
        for (final int[] region : sortedByStart(translationRegions(adapter))) {
            assertThat(cursor)
                    .as(
                            "translation regions must not overlap, or the complement below is "
                                    + "negative-length and silently drops source")
                    .isLessThanOrEqualTo(region[0]);
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
