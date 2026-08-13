package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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
     * The assertion every previous version should have made: each block's names equal <em>its own</em>
     * derived group.
     *
     * <p>This one test replaces four — a whole-set equality, a union, a disjointness check and a pair
     * of size assertions — and is strictly stronger than all of them together, which is the tell that
     * the earlier ones were the wrong shape. A review gate diagnosed it exactly: the paragraph asserts
     * a <em>mapping</em> from name to mechanism, and every check built for it verified
     * <em>aggregates</em> of that mapping. Aggregates over a mapping leave every permutation green by
     * construction, however many you add, which is why strengthening the total, then the padding
     * direction, then the derivation's positional honesty each closed a real hole and left the next
     * one open in the same place.
     *
     * <p>The gate proved it by moving {@code CardNotInHandException} from the rows-affected block into
     * the read block, leaving both headings untouched: the build stayed green. That is not an abstract
     * hole — it is the exact mis-filing the block's own text records as its own past mistake, so the
     * guard built to stop it recurring could not see it recurring.
     *
     * <p>Per-group equality catches an origin the adapter gains and the prose omits, a name the
     * prose invents, a name the prose deletes, and a name that moves between groups. It does not
     * catch a reworded or duplicated heading; {@link #namedInBlock} does that, by requiring exactly
     * one matching block. No total is given here on purpose — a count of attack shapes is one more
     * number to get wrong, and this slice has spent nine rounds proving that.
     */
    @Test
    @DisplayName("maps each origin to the mechanism that raises it, group by group")
    void shouldMapEachOriginToItsMechanism() throws IOException {
        final String adapter = adapterSource();
        final String paragraph = paragraphBesideTheTable(adrSource());

        assertThat(namedInBlock(paragraph, ROWS_BLOCK))
                .as(
                        "the rows-affected block must name exactly the origins raised from a "
                                + "rows-affected count of zero — no omission, no padding, and nothing "
                                + "that actually comes from the read")
                .isEqualTo(rowCountOrigins(adapter));

        assertThat(namedInBlock(paragraph, READ_BLOCK))
                .as(
                        "the read block must name exactly the origins raised from assertSeated. "
                                + "Moving a name between the two blocks was green until this "
                                + "assertion existed")
                .isEqualTo(seatReadOrigins(adapter));

        assertThat(paragraph)
                .as("the rows-affected heading must state its own group's size")
                .contains(numberWord(rowCountOrigins(adapter).size()) + " are raised from a rows-affected count of zero");
        assertThat(paragraph)
                .as("the read heading must state its own group's size")
                .contains(numberWord(seatReadOrigins(adapter).size()) + " are raised from a read this adapter makes itself");
    }

    /**
     * Every domain exception the adapter constructs outside a constraint translation. These are the
     * ones no row of ADR-023's table can cover, because no constraint was violated.
     */
    private static Set<String> nonConstraintOrigins(final String adapter) {
        final Set<String> all =
                domainExceptionsConstructedIn(adapter, wholeFileExcludingTranslations(adapter));
        assertThat(all)
                .as("the derivation must find something, or every assertion using it is vacuous")
                .isNotEmpty();
        return all;
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
     * <p>This is a <em>region</em> complement, not a name-level subtraction. An earlier version
     * computed {@link #nonConstraintOrigins} and then {@code removeAll}-ed the names found in
     * {@code assertSeated}, which dropped a type from this group whenever it appeared in that
     * method even if it was <em>also</em> constructed at a rows-affected position. Cutting regions
     * rather than names is what makes the two groups independently derived.
     *
     * <p>Deliberately no account here of which assertion a given mutation trips. The previous
     * version of this comment gave one, described a disjointness check as live, and was left behind
     * when that check was deleted one commit later — the eighth instance in this slice of a comment
     * outliving the mechanism it described. The assertion messages are the record of what fails and
     * where; they cannot go stale, because they are the thing that fires.
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
     * Ranges are half-open, so a range merely <em>adjacent</em> to the hole is preserved intact — a
     * range whose start coincides with the hole's start is truncated, not preserved.
     */
    private static Set<int[]> excluding(final Set<int[]> ranges, final int[] hole) {
        final Set<int[]> kept = new LinkedHashSet<>();
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

    /** Prefix of the block that enumerates the rows-affected group. Load-bearing. */
    private static final String ROWS_BLOCK = "are raised from a rows-affected count of zero";

    /** Prefix of the block that enumerates the read group. Load-bearing. */
    private static final String READ_BLOCK = "are raised from a read this adapter makes itself";

    /**
     * The exception types named in the one block beginning {@code prefix}.
     *
     * <p>Scoped to a single block, which is what makes the mapping checkable. An earlier version
     * unioned the tokens of <em>both</em> group blocks and so could never tell which mechanism the
     * prose assigned a name to — the hole a review gate walked through by migrating a name between
     * them. Reading one block at a time is also what keeps the universe sentence out of the way: it
     * backticks {@code IllegalStateException} and {@code IllegalArgumentException} precisely in order
     * to exclude them, and a whole-paragraph sweep flagged those two as padding.
     */
    private static Set<String> namedInBlock(final String paragraph, final String prefix) {
        final Set<String> found = new TreeSet<>();
        int blocks = 0;
        for (final String block : paragraph.split("\\n\\s*\\n")) {
            if (!headlineOf(block).contains(prefix)) {
                continue;
            }
            blocks++;
            final Matcher matcher = Pattern.compile("`(\\w+Exception)`").matcher(block);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        assertThat(blocks)
                .as(
                        "exactly one block must begin '%s'. Zero means the heading was reworded and "
                                + "this check would go vacuous; more than one means the paragraph was "
                                + "duplicated, which has happened once already in this slice",
                        prefix)
                .isEqualTo(1);
        assertThat(found)
                .as("the block beginning '%s' must name its members in backticks", prefix)
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
        final Set<int[]> regions = new LinkedHashSet<>();
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
        final Set<int[]> keep = new LinkedHashSet<>();
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

    /**
     * The bolded lead-in of a block, which is where a group block declares what it is.
     *
     * <p>Matching a group block on this rather than on a prefix containing the number word is
     * deliberate. The prefixes used to read {@code "**Seven are raised from"}, so a legitimate change
     * in a group's size made the block unfindable and reported as a reworded heading — fail-safe, but
     * with the wrong diagnosis. Matching the invariant half means a size change now fails as the size
     * mismatch it is.
     */
    private static String headlineOf(final String block) {
        final String trimmed = block.trim();
        final int stop = trimmed.indexOf(":**");
        return stop < 0 ? trimmed : trimmed.substring(0, stop);
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
