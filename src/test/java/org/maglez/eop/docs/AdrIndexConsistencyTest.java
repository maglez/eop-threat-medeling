package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the ADR index in {@code docs/adr/README.md} honest about the files it lists (EOP-32).
 *
 * <p>"Add a row to the index above" is step 5 of that README's own instructions for adding an
 * ADR, and it is the kind of step a human executes correctly on the day and then forgets on
 * every subsequent amendment. It had already been missed twice when this class was written:
 * ADR-002's index cell read a bare {@code Accepted} from 2026-07-27, months after the file
 * recorded that its framework version had been superseded, and ADR-012's read an undated
 * {@code Accepted (amended)} while the file recorded that the entire deployment target had
 * been withdrawn. A reader trusting the index in either case would have drawn a decision from
 * it that the ADR itself had already retracted.
 *
 * <p>So the index is checked mechanically rather than by instruction. The four invariants are
 * deliberately narrow, because an over-strict version of this test would be worked around
 * instead of obeyed: the status cell does not have to repeat the ADR's status line, it only
 * has to agree with it on the two things a reader scanning the table actually relies on — the
 * leading status word, and whether an amendment has a date. That leaves the sanctioned short
 * form intact (ADR-003 and ADR-008 both summarise a longer status line in a few words) while
 * still failing the build for both of the defects above.
 *
 * <p>This is a plain JUnit test with no Spring context. Surefire runs with the working
 * directory set to the project base directory, so the relative path resolves.
 */
@DisplayName("ADR index in docs/adr/README.md")
class AdrIndexConsistencyTest {

    /** Directory holding the ADRs and their index. */
    private static final Path ADR_DIRECTORY = Path.of("docs", "adr");

    /** The index itself. */
    private static final Path INDEX = ADR_DIRECTORY.resolve("README.md");

    /** Matches an ADR file name, capturing its three-digit number. */
    private static final Pattern FILE_NAME = Pattern.compile("^ADR-(\\d{3})-.+\\.md$");

    /** Matches an index row, capturing the ADR number and the link target. */
    private static final Pattern INDEX_ROW = Pattern.compile("^\\|\\s*\\[(\\d{3})\\]\\(([^)]+)\\)\\s*\\|.*");

    /** Matches the {@code **Status:**} line of an ADR, with or without a leading list marker. */
    private static final Pattern STATUS_LINE = Pattern.compile("^\\s*(?:-\\s*)?\\*\\*Status:\\*\\*\\s*(.*)$");

    /** Matches the field that always follows the status block, and so terminates it. */
    private static final Pattern STATUS_TERMINATOR =
            Pattern.compile("^\\s*(?:-\\s*)?(?:\\*\\*(?:Date|Deciders):\\*\\*|#).*$");

    /** Matches an ISO date anywhere in a line. */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Matches the leading run of letters in a status, ignoring markdown emphasis. */
    private static final Pattern LEADING_WORD = Pattern.compile("^[*_\\s]*([A-Za-z]+)");

    /**
     * Column index of the Status cell, once a row is split on the pipe character.
     *
     * <p>The index table's columns are {@code | ADR | Decision | Status | Implemented? |}, and
     * splitting that on {@code |} yields {@code ["", " ADR ", " Decision ", " Status ",
     * " Implemented? "]} — an empty leading element because the row starts with a pipe, so the
     * third data column lands at index 3. Adding a column to the table before Status means
     * moving this constant.
     */
    private static final int STATUS_COLUMN = 3;

    @Test
    @DisplayName("lists every ADR file exactly once, and links only to files that exist")
    void shouldListEveryAdrExactlyOnce() throws IOException {
        final Map<String, String> rows = indexRows();
        assertThat(rows.keySet())
                .as("an ADR with no index row is invisible to anyone reading the index")
                .containsExactlyInAnyOrderElementsOf(adrFiles().keySet());

        for (final Map.Entry<String, String> row : rows.entrySet()) {
            assertThat(ADR_DIRECTORY.resolve(row.getValue()))
                    .as("ADR-%s index row links to %s", row.getKey(), row.getValue())
                    .exists();
        }
    }

    @Test
    @DisplayName("agrees with each ADR on its leading status word")
    void shouldAgreeOnTheStatusWord() throws IOException {
        final Map<String, String> files = adrFiles();
        for (final Map.Entry<String, String> cell : statusCells().entrySet()) {
            final String number = cell.getKey();
            final String content = requireIndexedFile(files, number);
            final String status = statusOf(content);
            assertThat(leadingWord(cell.getValue()))
                    .as(
                            "ADR-%s: index says %s, the file's status line says %s",
                            number, cell.getValue(), status)
                    .isEqualTo(leadingWord(status));
        }
    }

    @Test
    @DisplayName("carries the date of every dated amendment, so a stale row cannot look current")
    void shouldCarryAmendmentDates() throws IOException {
        final Map<String, String> files = adrFiles();
        for (final Map.Entry<String, String> cell : statusCells().entrySet()) {
            final String number = cell.getKey();
            final String status = statusOf(requireIndexedFile(files, number));
            final List<String> dates = datesIn(status);
            if (dates.isEmpty()) {
                continue;
            }
            assertThat(cell.getValue())
                    .as(
                            "ADR-%s: the file's status line is dated (%s) but the index cell %s "
                                    + "does not say when — an undated amendment reads as current",
                            number, String.join(", ", dates), cell.getValue())
                    .contains(dates);
        }
    }

    @Test
    @DisplayName("is parseable at all: every ADR has exactly one status line to compare against")
    void shouldFindOneStatusLinePerAdr() throws IOException {
        for (final Map.Entry<String, String> file : adrFiles().entrySet()) {
            assertThatCode(() -> statusOf(file.getValue()))
                    .as("ADR-%s must declare a **Status:** line in the house structure", file.getKey())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("gives every row the column count the header declares, so no cell is silently dropped")
    void shouldGiveEveryRowTheDeclaredColumnCount() throws IOException {
        final List<String> lines = indexLines();
        final String header = lines.stream()
                .filter(line -> line.startsWith("| ADR |"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the ADR index has lost its table header row"));
        final int declared = dataCellCount(header);

        for (final String line : lines) {
            final Matcher row = INDEX_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            assertThat(dataCellCount(line))
                    .as(
                            "ADR-%s's index row has %d cells against the header's %d. Neither direction is"
                                    + " visible in the rendered table: GFM ignores cells beyond the header count,"
                                    + " so surplus text vanishes from the render while remaining in the source,"
                                    + " and it silently inserts empty cells when a row is short, so a dropped"
                                    + " cell reads as a blank one. EOP-92 hit the first mode — 6.4 KB of this"
                                    + " row's own prose pasted past its terminating pipe, still passing a green"
                                    + " build, because the other four invariants here look at content and none"
                                    + " looked at shape.",
                            row.group(1),
                            dataCellCount(line),
                            declared)
                    .isEqualTo(declared);
        }
    }

    /**
     * Counts the data cells in a GitHub-Flavoured Markdown table row.
     *
     * <p>GFM treats the leading and trailing pipes as optional delimiters rather than as cell
     * boundaries, so {@code | a | b |} and {@code a | b} both hold two cells. Stripping one
     * pipe from each end before splitting therefore counts what a renderer counts, which is the
     * only definition that matters here: the failure this guards against is a renderer silently
     * discarding a cell the source still contains.
     *
     * @param row one line of the index table
     * @return the number of cells a Markdown renderer will read from it
     */
    private static int dataCellCount(final String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.split("\\|", -1).length;
    }

    /**
     * Returns the text of the ADR the given index row points at, failing the assertion rather
     * than throwing {@link NullPointerException} when no such file exists.
     *
     * <p>{@link #shouldListEveryAdrExactlyOnce()} already catches a row with no file, but JUnit
     * tests are independent: the comparison tests must not collapse into an opaque
     * {@code NullPointerException} when run against the same broken index.
     *
     * @param files each ADR's number mapped to the whole text of its file
     * @param number the three-digit ADR number taken from the index row
     * @return the file's text, never {@code null}
     */
    private static String requireIndexedFile(final Map<String, String> files, final String number) {
        final String content = files.get(number);
        assertThat(content)
                .as("ADR-%s: the index has a row for it, but docs/adr holds no such file", number)
                .isNotNull();
        return content;
    }

    /** Returns each ADR's number mapped to the whole text of its file. */
    private static Map<String, String> adrFiles() throws IOException {
        final Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(ADR_DIRECTORY)) {
            for (final Path entry : entries.sorted().toList()) {
                final Matcher matcher = FILE_NAME.matcher(entry.getFileName().toString());
                if (matcher.matches()) {
                    files.put(matcher.group(1), Files.readString(entry));
                }
            }
        }
        assertThat(files)
                .as("no ADR files found in %s — is the working directory the project root?", ADR_DIRECTORY.toAbsolutePath())
                .isNotEmpty();
        return files;
    }

    /** Returns each indexed ADR's number mapped to the file its row links to. */
    private static Map<String, String> indexRows() throws IOException {
        final Map<String, String> rows = new LinkedHashMap<>();
        for (final String line : indexLines()) {
            final Matcher matcher = INDEX_ROW.matcher(line);
            if (matcher.matches()) {
                assertThat(rows).as("ADR-%s appears in the index more than once", matcher.group(1))
                        .doesNotContainKey(matcher.group(1));
                rows.put(matcher.group(1), matcher.group(2));
            }
        }
        return rows;
    }

    /** Returns each indexed ADR's number mapped to the text of its Status cell. */
    private static Map<String, String> statusCells() throws IOException {
        final Map<String, String> cells = new LinkedHashMap<>();
        for (final String line : indexLines()) {
            final Matcher matcher = INDEX_ROW.matcher(line);
            if (matcher.matches()) {
                final String[] columns = line.split("\\|");
                assertThat(columns.length)
                        .as("ADR-%s index row has too few columns: %s", matcher.group(1), line)
                        .isGreaterThan(STATUS_COLUMN);
                cells.put(matcher.group(1), columns[STATUS_COLUMN].trim());
            }
        }
        return cells;
    }

    /** Returns the lines of the index, read once for whichever of the parsers above needs them. */
    private static List<String> indexLines() throws IOException {
        return Files.readAllLines(INDEX);
    }

    /**
     * Returns an ADR's status as one string, joining the wrapped continuation lines that follow
     * the {@code **Status:**} marker up to the {@code **Date:**} field or a blank line.
     *
     * <p>Throws if the document does not hold exactly one status line. Zero means the ADR has
     * dropped the house structure and there is nothing for the index to agree with. More than
     * one means the index cell has to be compared against an arbitrary choice between them,
     * which is how a superseded status ends up being read as the live one.
     *
     * @throws IllegalArgumentException if the document has no status line, or several
     */
    private static String statusOf(final String document) {
        final List<String> lines = document.lines().toList();
        final List<Integer> markers = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (STATUS_LINE.matcher(lines.get(index)).matches()) {
                markers.add(index);
            }
        }
        if (markers.isEmpty()) {
            throw new IllegalArgumentException("no **Status:** line found");
        }
        if (markers.size() > 1) {
            throw new IllegalArgumentException(
                    "several **Status:** lines found, on lines "
                            + markers.stream().map(line -> String.valueOf(line + 1)).toList()
                            + " — the index cannot agree with more than one");
        }
        final int start = markers.get(0);
        final Matcher matcher = STATUS_LINE.matcher(lines.get(start));
        assertThat(matcher.matches()).as("the located status line still matches").isTrue();
        final StringBuilder status = new StringBuilder(matcher.group(1).strip());
        for (int next = start + 1; next < lines.size(); next++) {
            final String line = lines.get(next);
            if (line.isBlank() || STATUS_TERMINATOR.matcher(line).matches()) {
                break;
            }
            status.append(' ').append(line.strip());
        }
        return status.toString().strip();
    }

    /** Returns the leading status word, lower-cased and stripped of markdown and punctuation. */
    private static String leadingWord(final String status) {
        final Matcher matcher = LEADING_WORD.matcher(status);
        assertThat(matcher.find()).as("status %s does not begin with a word", status).isTrue();
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    /** Returns every ISO date in the given status text, in order of appearance, without duplicates. */
    private static List<String> datesIn(final String status) {
        final List<String> dates = new ArrayList<>();
        final Matcher matcher = ISO_DATE.matcher(status);
        while (matcher.find()) {
            if (!dates.contains(matcher.group())) {
                dates.add(matcher.group());
            }
        }
        return dates;
    }
}
