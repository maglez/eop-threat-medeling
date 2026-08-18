package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards prose about the deck against arithmetic that the deck size has outgrown (EOP-93).
 *
 * <p>The deck has been trimmed twice: EOP-69 took it from 78 cards to 74, and EOP-75 removed the six Aces to leave 68
 * (ADR-041). Each trim left prose behind. EOP-92's review found three documents simultaneously stale with a green
 * build, and EOP-93 then found fifteen more places — five of them in Java javadoc, four of them in claims no reviewer
 * had listed. Nothing in the build noticed any of it, because a comment cannot fail to compile.
 *
 * <p>The recurring defect was never the digits. It was the <em>universal quantifier</em>. Prose kept asserting that
 * the deal is uneven at <em>every</em> supported table size, which was true at 74 cards and false at 68: 68 divides
 * evenly by four, so at that one table size every seat holds seventeen and the final trick is full. Sentences of that
 * shape survived both trims because they name no number at all. Two concrete examples, both live on {@code main}
 * until EOP-93: "At 74 cards, all four supported table sizes (3, 4, 5 and 6 players) produce unequal hands", and
 * "EOP-14 deals the whole deck out, so at every supported player count (3, 4, 5 and 6) the hands are unequal".
 *
 * <p>So the first invariant here does not look for stale numbers. It looks for a universal claim over table sizes
 * sitting in the same sentence as a claim that the deal is uneven, and fails only when some supported table size
 * actually does divide the deck evenly. It is derived from {@link #CURRENT_DECK_SIZE}, so the next trim needs one
 * number changed rather than a re-reading of every document. The second invariant is the cheap one the first cannot
 * express: no superseded deck size stated in Java prose, where javadoc went stale five times in a row.
 *
 * <p>Be precise about what "a universal claim" means here, because the phrase flatters the implementation. {@link
 * #EVERY_TABLE_SIZE} is a list of the phrasings that have actually gone stale in this repository, not a semantic
 * analysis of quantification. "At all four table sizes", "at each player count" and "the final trick is always
 * short" all escape it. That is a deliberate trade — a matcher general enough to catch every phrasing of a universal
 * claim would also catch the many true ones — but it has a consequence worth stating plainly: <em>a green build is
 * not proof that no stale universal claim exists.</em> It proves only that the specific shapes which have burned us
 * before are absent. Extend the pattern when a new shape appears; do not read the pass as a clean bill of health.
 *
 * <p>The scope is deliberately narrow, because an over-strict version of this test would be worked around instead of
 * obeyed. It matches per sentence rather than per paragraph, so an unrelated later sentence cannot supply the second
 * half of a contradiction; it says nothing about the many correct historical references in {@code docs/} — the
 * author's own "74, not 78 cards" quote, ADR-041, and the dated amendment blocks all state superseded sizes on
 * purpose; and it does not police ADR-023's original body, which still reads 78 cards under EOP-69's explicit
 * {@code Amends:} header and is correct as it stands.
 *
 * <p>This is a plain JUnit test with no Spring context. Surefire runs with the working directory set to the project
 * base directory, so the relative paths resolve.
 */
@DisplayName("Deck arithmetic claims in prose")
class DeckArithmeticClaimsTest {

    /** The deck as seeded today: 68 cards, after EOP-75 removed the six Aces. See ADR-041. */
    private static final int CURRENT_DECK_SIZE = 68;

    /**
     * Deck sizes that were once current and are now wrong to state as current: 78 as printed and originally seeded,
     * 74 after EOP-69 dropped the four cards the physical deck does not have.
     */
    private static final List<Integer> SUPERSEDED_DECK_SIZES = List.of(74, 78);

    /** Player counts the game supports, per ADR-023. The deal arithmetic differs at each. */
    private static final List<Integer> SUPPORTED_TABLE_SIZES = List.of(3, 4, 5, 6);

    /** Documentation root. Markdown here is prose a reader is expected to trust. */
    private static final Path DOCS_DIRECTORY = Path.of("docs");

    /** Source root. Javadoc here is prose a reader is expected to trust just as much. */
    private static final Path SOURCE_DIRECTORY = Path.of("src");

    /**
     * Files allowed to state a superseded deck size, and to write the sentences the first invariant forbids.
     *
     * <p>{@code DeckTrimMigrationRoundTripTest} rolls the ace-removal migration back and asserts the deck passes
     * through 74 cards and then 78, so those numbers are its assertions rather than a stale comment. This test is on
     * the list for an unavoidable reason: a guard has to quote what it forbids. Its javadoc names the claims that
     * were live on {@code main}, and {@link #shouldRecogniseTheHistoricalDefectItGuardsAgainst()} feeds them back in
     * to prove the matcher still fires. Without the exemption this test would fail on its own evidence, and the
     * tempting fix — deleting the examples — would leave a regex nobody could check.
     *
     * <p>The entries are repository-relative paths rather than bare filenames. A basename match would exempt any
     * future file anywhere in the tree that happened to share a name with one of these two, which is the weakest
     * discriminator available and costs nothing to improve.
     */
    private static final List<String> EXEMPT_FILES = List.of(
            "src/test/java/org/maglez/eop/migration/DeckTrimMigrationRoundTripTest.java",
            "src/test/java/org/maglez/eop/docs/DeckArithmeticClaimsTest.java");

    /**
     * A claim that ranges over every supported table size. The enumeration is included because prose reaches for
     * "3, 4, 5 and 6 players" as often as it reaches for a quantifier word, and both forms went stale.
     */
    private static final Pattern EVERY_TABLE_SIZE = Pattern.compile(
            "all (?:four|the four) supported table sizes"
                    + "|at any supported table size"
                    + "|at every supported table size"
                    + "|every supported table size"
                    + "|at any player count"
                    + "|at every player count"
                    + "|at every supported player count"
                    + "|every supported player count"
                    + "|\\b3,\\s*4,\\s*5,?\\s*(?:and|or)\\s*6\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A claim that no supported table size divides the deck. The negation lives in the quantifier here, so this pairs
     * with the opposite predicate to {@link #EVERY_TABLE_SIZE} — see {@link #DIVIDES_EVENLY}.
     */
    private static final Pattern NO_TABLE_SIZE = Pattern.compile(
            "no supported table size|none of the supported table sizes",
            Pattern.CASE_INSENSITIVE);

    /**
     * A claim that the deal comes out uneven. Note that "unequal" is matched but "near-equal" is not, so PRD-eop's
     * true sentence about 68 producing "equal or near-equal hands" is left alone.
     */
    private static final Pattern DEAL_IS_UNEVEN = Pattern.compile(
            "unequal"
                    + "|do(?:es)? not divide"
                    + "|short final trick"
                    + "|(?:final|last) trick is short",
            Pattern.CASE_INSENSITIVE);

    /** A claim that the deck does divide evenly — false only when paired with {@link #NO_TABLE_SIZE}. */
    private static final Pattern DIVIDES_EVENLY = Pattern.compile(
            "divides? (?:evenly|equally)",
            Pattern.CASE_INSENSITIVE);

    /** Jira keys are stripped before any number is read, so "EOP-74" cannot be mistaken for a deck size. */
    private static final Pattern JIRA_KEY = Pattern.compile("EOP-\\d+", Pattern.CASE_INSENSITIVE);

    /** Splits a paragraph into sentences. Sentence scope is what keeps the first invariant honest. */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    @Test
    @DisplayName("never claims the deal is uneven at every supported table size, when one of them divides evenly")
    void shouldNotClaimTheDealIsUnevenAtEveryTableSize() throws IOException {
        final List<Integer> evenTableSizes = tableSizesDividingTheDeckEvenly();
        assertThat(evenTableSizes)
                .as("this invariant only bites while some supported table size divides %d evenly; if a future trim "
                        + "leaves none, delete the test rather than leaving it passing vacuously", CURRENT_DECK_SIZE)
                .isNotEmpty();

        final List<Path> files = prosePaths();
        assertThat(files)
                .as("found no prose to check under %s or %s — is the working directory the project root?",
                        DOCS_DIRECTORY, SOURCE_DIRECTORY)
                .isNotEmpty();

        final List<String> offences = new ArrayList<>();
        for (final Path file : files) {
            if (isExempt(file)) {
                continue;
            }
            for (final String sentence : sentencesOf(Files.readAllLines(file))) {
                if (contradictsTheDeal(sentence)) {
                    offences.add("%s: %s".formatted(file, sentence));
                }
            }
        }

        assertThat(offences)
                .as("a sentence claims the deal is uneven across every supported table size, but %d divides evenly "
                        + "by %s — at that table size every seat holds the same number of cards and the final trick "
                        + "is full. This is the wording that survived both deck trims untouched, because it names no "
                        + "number. Qualify it to the table sizes whose deal is actually uneven.",
                        CURRENT_DECK_SIZE, evenTableSizes)
                .isEmpty();
    }

    @Test
    @DisplayName("states no superseded deck size in Java prose")
    void shouldNotStateASupersededDeckSizeInJavaProse() throws IOException {
        final List<Path> files = javaPaths();
        assertThat(files)
                .as("found no Java under %s — is the working directory the project root?", SOURCE_DIRECTORY)
                .isNotEmpty();

        final List<String> offences = new ArrayList<>();
        for (final Path file : files) {
            if (isExempt(file)) {
                continue;
            }
            final List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                final String line = JIRA_KEY.matcher(lines.get(index)).replaceAll("");
                for (final int superseded : SUPERSEDED_DECK_SIZES) {
                    if (statesTheNumber(line, superseded)) {
                        offences.add("%s:%d states %d: %s".formatted(file, index + 1, superseded, line.trim()));
                    }
                }
            }
        }

        assertThat(offences)
                .as("Java prose states a superseded deck size. The deck holds %d cards (ADR-041); %s are historical. "
                        + "Javadoc in Trick, Hands, SeatOrder, GetScoreUseCase and NoTamperingCardDealtException all "
                        + "went stale this way and the build stayed green, as did a GlobalExceptionHandlerTest fixture "
                        + "that constructed the exception with a deck of 78. Only %s may state a superseded size.",
                        CURRENT_DECK_SIZE, SUPERSEDED_DECK_SIZES, EXEMPT_FILES)
                .isEmpty();
    }

    /**
     * Reports whether a file is one of the two that may state what this test otherwise forbids.
     *
     * <p>Compares a repository-relative path, not a bare filename, so the exemption cannot be inherited by an
     * unrelated future file that happens to share a name.
     *
     * @param file the file being scanned
     * @return true when the file is exempt
     */
    private static boolean isExempt(final Path file) {
        final String relative = file.normalize().toString().replace('\\', '/');
        return EXEMPT_FILES.stream().anyMatch(relative::endsWith);
    }

    @Test
    @DisplayName("still recognises the three claims that were live on main before EOP-93")
    void shouldRecogniseTheHistoricalDefectItGuardsAgainst() {
        final String fromAdr023 =
                "At 74 cards, all four supported table sizes (3, 4, 5 and 6 players) produce unequal hands.";
        final String fromAdr019 =
                "EOP-14 deals the whole deck out, so at every supported player count the hands are unequal.";
        final String fromThePrd =
                "At 74 cards no supported table size divides evenly, so a player can win the trick they end on.";
        final String trueOfSixtyEight =
                "At 68 cards, all four supported table sizes (3, 4, 5 and 6 players) produce equal or near-equal hands.";
        final String properlyQualified =
                "At 68 cards three of the four supported table sizes produce unequal hands; four players is the "
                        + "exception, because 68 divides evenly by four.";

        assertThat(contradictsTheDeal(fromAdr023))
                .as("the ADR-023 wording EOP-93 fixed must still be caught, or this guard has rotted into a no-op")
                .isTrue();
        assertThat(contradictsTheDeal(fromAdr019))
                .as("the ADR-019 wording EOP-93 fixed must still be caught — it names no number at all, which is "
                        + "precisely why two deck trims sailed past it")
                .isTrue();
        assertThat(contradictsTheDeal(fromThePrd))
                .as("the PRD wording EOP-93 fixed must still be caught — it puts the negation in the quantifier")
                .isTrue();
        assertThat(contradictsTheDeal(trueOfSixtyEight))
                .as("a universal claim that is true at 68 cards must not be flagged; 'near-equal' is not 'unequal'")
                .isFalse();
        assertThat(contradictsTheDeal(properlyQualified))
                .as("the corrected wording must not be flagged, or the guard would forbid its own fix")
                .isFalse();
    }

    /**
     * Reports whether one sentence claims the deal is uneven across table sizes that include one dividing evenly.
     *
     * <p>Two shapes are wrong. A positive quantifier ("all four supported table sizes") paired with an uneven-deal
     * predicate, and a negative quantifier ("no supported table size") paired with the opposite predicate. Both say
     * the same false thing.
     *
     * @param sentence a single normalised sentence
     * @return true when the sentence contradicts the deal arithmetic of {@link #CURRENT_DECK_SIZE}
     */
    private static boolean contradictsTheDeal(final String sentence) {
        final String text = JIRA_KEY.matcher(sentence).replaceAll("");
        final boolean overstatesUnevenness =
                EVERY_TABLE_SIZE.matcher(text).find() && DEAL_IS_UNEVEN.matcher(text).find();
        final boolean deniesEvenDivision =
                NO_TABLE_SIZE.matcher(text).find() && DIVIDES_EVENLY.matcher(text).find();
        return overstatesUnevenness || deniesEvenDivision;
    }

    /**
     * Returns the supported table sizes that divide the current deck without remainder.
     *
     * @return the evenly dividing table sizes, in ascending order
     */
    private static List<Integer> tableSizesDividingTheDeckEvenly() {
        return SUPPORTED_TABLE_SIZES.stream().filter(size -> CURRENT_DECK_SIZE % size == 0).toList();
    }

    /**
     * Reports whether a line states a number as a standalone token, in digits or in words.
     *
     * <p>The word-boundary match is what keeps UUID fixtures out of the results: the {@code 74} inside
     * {@code b9f389069b74} has a word character before it and so is not a standalone token. The lookarounds extend
     * that to decimals, which a word boundary does not exclude on its own — {@code 0.74} and {@code 74.5} are one
     * number each, not a mention of the old deck size.
     *
     * @param line a line of source, with Jira keys already removed
     * @param number the number to look for
     * @return true when the line states that number
     */
    private static boolean statesTheNumber(final String line, final int number) {
        final String digits = "(?<![\\d.])\\b" + number + "\\b(?!\\.\\d)";
        if (Pattern.compile(digits).matcher(line).find()) {
            return true;
        }
        final String spelled = spell(number);
        return spelled != null && line.toLowerCase(Locale.ROOT).contains(spelled);
    }

    /**
     * Spells the superseded deck sizes, which prose is as likely to write in words as in digits.
     *
     * @param number the number to spell
     * @return the spelled form, or null when there is no spelling worth checking
     */
    private static String spell(final int number) {
        return switch (number) {
            case 74 -> "seventy-four";
            case 78 -> "seventy-eight";
            default -> null;
        };
    }

    /**
     * Splits a file into sentences, joining wrapped prose first.
     *
     * <p>Markdown and javadoc both wrap freely, so a claim routinely straddles two lines: the PRD's true sentence
     * about 68 cards breaks after "produce equal or". A line-scoped matcher would miss exactly the paragraph-length
     * claims that go stale, so lines are joined into paragraphs on blank lines and then split into sentences.
     *
     * @param lines the raw lines of the file
     * @return the sentences of the file, normalised
     */
    private static List<String> sentencesOf(final List<String> lines) {
        final List<String> sentences = new ArrayList<>();
        final StringBuilder paragraph = new StringBuilder();
        for (final String rawLine : lines) {
            final String line = normalise(rawLine);
            if (line.isEmpty()) {
                addSentences(paragraph.toString(), sentences);
                paragraph.setLength(0);
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            }
        }
        addSentences(paragraph.toString(), sentences);
        return sentences;
    }

    /**
     * Splits one paragraph into sentences and adds the non-blank ones to the accumulator.
     *
     * @param paragraph the joined paragraph
     * @param sentences the accumulator to add to
     */
    private static void addSentences(final String paragraph, final List<String> sentences) {
        if (paragraph.isBlank()) {
            return;
        }
        for (final String sentence : SENTENCE_BOUNDARY.split(paragraph)) {
            if (!sentence.isBlank()) {
                sentences.add(sentence.trim());
            }
        }
    }

    /**
     * Strips the markers that markdown and javadoc put in front of prose, so a claim reads the same either way.
     *
     * <p>Blockquote and heading markers go, then one leading comment marker, then emphasis and code ticks — bold is
     * how prose stresses the very quantifiers this test looks for, and "all four supported table sizes" must match
     * whether or not someone wrapped it in asterisks.
     *
     * @param rawLine a line as read from the file
     * @return the line's prose, with markers removed and whitespace collapsed
     */
    private static String normalise(final String rawLine) {
        String text = rawLine.trim();
        while (text.startsWith(">") || text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        if (text.startsWith("/**") || text.startsWith("<p>")) {
            text = text.substring(3);
        } else if (text.startsWith("/*") || text.startsWith("//")) {
            text = text.substring(2);
        } else if (text.startsWith("*")) {
            text = text.substring(1);
        }
        return text.replace("*/", " ")
                .replace("*", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Collects the prose the first invariant reads: markdown under {@code docs/} and Java under {@code src/}.
     *
     * @return every markdown and Java file, sorted for a stable failure message
     * @throws IOException if a directory cannot be walked
     */
    private static List<Path> prosePaths() throws IOException {
        final List<Path> paths = new ArrayList<>(markdownPaths());
        paths.addAll(javaPaths());
        return paths;
    }

    /**
     * Collects the markdown files under {@code docs/}.
     *
     * @return every markdown file, sorted
     * @throws IOException if the directory cannot be walked
     */
    private static List<Path> markdownPaths() throws IOException {
        try (Stream<Path> walk = Files.walk(DOCS_DIRECTORY)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Collects the Java files under {@code src/}.
     *
     * @return every Java file, sorted
     * @throws IOException if the directory cannot be walked
     */
    private static List<Path> javaPaths() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCE_DIRECTORY)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
