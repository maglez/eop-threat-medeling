package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the label text of every Mermaid {@code sequenceDiagram} under {@code docs/} to the two characters Mermaid cannot carry
 * there (EOP-000).
 *
 * <p>Four of the twelve sequence diagrams in {@code docs/architecture/runtime-view.md} did not render at all, and the repository
 * had no way to notice. Nothing in the build or in CI parses or lints Mermaid, no Mermaid package exists anywhere in the tree, and
 * a broken diagram is invisible until a human opens the page on GitHub — so the defect survived every green build between the day
 * it was introduced and the day it was reported by eye.
 *
 * <p>Mermaid's sequence grammar tokenises label text as {@code [^#\n;]*}. Both excluded characters are hazards, but they fail in
 * opposite directions and the quiet one is the more dangerous:
 *
 * <ul>
 *   <li>A {@code ;} ends the token, so the rest of the line is parsed as a fresh statement and the diagram fails outright. It was
 *   measured against Mermaid 11.17.0 to break thirteen distinct text-carrying constructs — message text, note text,
 *   {@code alt}, {@code else}, {@code opt}, {@code loop}, {@code par}, {@code and}, {@code critical}, {@code break},
 *   {@code title}, {@code participant … as …} and {@code box} — which is why this test reads whole lines rather than only the
 *   text after a colon.</li>
 *   <li>A {@code #} parses cleanly and then silently truncates the label when it renders: {@code issue #42} was rendered as
 *   {@code issue} at both {@code securityLevel=strict} and {@code securityLevel=loose}, with the rest of the string absent from
 *   the SVG. Nothing warns. This is strictly worse than the {@code ;} case, because a loud failure gets reported and a missing
 *   half-sentence does not.</li>
 * </ul>
 *
 * <p>Between them the two rules also close off Mermaid's documented {@code #NN;} entity escape, which is unusable here by
 * construction: without its trailing {@code ;} it truncates, and with it the line no longer parses. Spell the character out or
 * reword.
 *
 * <p>The scope is deliberately narrow in three ways, because an over-strict version of this test would be worked around instead of
 * obeyed. It checks only {@code sequenceDiagram} fences — {@code docs/architecture/C4-Diagrams.md} carries a flowchart node label
 * containing {@code &lt;} inside a quoted string, which renders correctly and must not be failed. It ignores {@code %%} comment
 * and directive lines, since an {@code init} directive may legitimately carry CSS. And it says nothing about {@code <} or
 * {@code >}: raw {@code List<Trick>} and raw {@code a <> b} were rendered at both security levels and displayed intact, because
 * Mermaid's sequence renderer escapes angle brackets itself. An earlier draft of this guard did gate them, on the inference that
 * {@code <br/>} being honoured meant {@code <Trick>} would be swallowed as a tag. Direct rendering disproved it, so the rule was
 * dropped rather than shipped on reasoning alone.
 *
 * <p>Surefire runs with the working directory set to the project base directory, so the relative path resolves.
 */
@DisplayName("Mermaid sequence-diagram labels under docs/")
class MermaidSequenceTextTest {

    /**
     * The documentation tree, walked recursively for Markdown. Relative to the project base directory.
     */
    private static final Path DOCUMENTATION_DIRECTORY = Path.of("docs");

    /**
     * Opening delimiter of a Mermaid code fence, matched after stripping leading whitespace.
     */
    private static final String MERMAID_FENCE = "```mermaid";

    /**
     * Any fence delimiter. A fence closes on the first following line that starts with three backticks.
     */
    private static final String FENCE_DELIMITER = "```";

    /**
     * The directive that identifies a fence as a sequence diagram, as opposed to a flowchart or an ER diagram.
     */
    private static final String SEQUENCE_DIAGRAM = "sequenceDiagram";

    /**
     * Mermaid's comment prefix, which also introduces an {@code %%{init: …}%%} configuration directive. Both are skipped: a
     * directive may legitimately carry CSS, and CSS needs semicolons.
     */
    private static final String COMMENT_PREFIX = "%%";

    /**
     * Delimiter of the optional YAML front matter block that may open a fence. Front matter is configuration rather than label
     * text, so it is skipped along with everything before the diagram directive.
     */
    private static final String FRONT_MATTER_DELIMITER = "---";

    /**
     * Terminates Mermaid's label token, so the remainder of the line is parsed as a statement and the diagram fails to render.
     */
    private static final String SEMICOLON = ";";

    /**
     * Parses cleanly and then silently truncates the rendered label at this character.
     */
    private static final String HASH = "#";

    /**
     * A floor on the number of sequence diagrams found, so this test cannot pass by quietly matching nothing — the failure mode
     * ADR-006 records for the branch-coverage limit, and the reason {@code AdrIndexConsistencyTest} carries its own
     * parser-still-works check.
     *
     * <p>Fourteen is the count at the time of writing: twelve in {@code docs/architecture/runtime-view.md}, one in
     * {@code docs/adr/ADR-023-…} and one in {@code docs/architecture/C4-Diagrams.md}. Raise it when diagrams are added; lower it
     * only alongside a deliberate decision to delete one.
     */
    private static final int MINIMUM_SEQUENCE_DIAGRAMS = 14;

    @Test
    @DisplayName("carry no semicolon, which would end the label token and stop the diagram rendering")
    void shouldNotCarryASemicolon() {
        final List<String> offenders = labelLines().stream()
                .filter(line -> line.content().contains(SEMICOLON))
                .map(SequenceDiagramLine::describe)
                .toList();

        assertThat(offenders)
                .as("Mermaid tokenises sequence-diagram label text as [^#\\n;]*, so a semicolon ends the token and everything"
                        + " after it on the line is parsed as a fresh statement. The diagram then fails to render entirely — the"
                        + " reader sees a raw code block or an error box, not a picture. Thirteen constructs are affected, not"
                        + " just messages and notes: alt, else, opt, loop, par, and, critical, break, title, participant … as …"
                        + " and box all break the same way. This is how four of the twelve diagrams in"
                        + " docs/architecture/runtime-view.md came to be broken for months without any build noticing."
                        + " Delete the semicolon (a <br/> beside it already breaks the line), or replace it with an em dash."
                        + " Mermaid's #59; entity escape is not an option — see the note on the hash rule.")
                .isEmpty();
    }

    @Test
    @DisplayName("carry no hash, which would silently truncate the rendered label")
    void shouldNotCarryAHash() {
        final List<String> offenders = labelLines().stream()
                .filter(line -> line.content().contains(HASH))
                .map(SequenceDiagramLine::describe)
                .toList();

        assertThat(offenders)
                .as("A hash parses cleanly and then disappears at render time, taking the rest of the label with it: 'issue #42'"
                        + " was rendered as 'issue' at both securityLevel=strict and securityLevel=loose, and the full string was"
                        + " absent from the SVG. Nothing warns, so this is worse than the semicolon rule it sits beside — a"
                        + " diagram that fails loudly gets reported, whereas a diagram missing half a sentence looks finished."
                        + " Spell the character out or reword. Note this also rules out Mermaid's documented #NN; entity escape,"
                        + " which cannot work in a sequence label: without the trailing semicolon it truncates here, and with it"
                        + " the line no longer parses at all.")
                .isEmpty();
    }

    @Test
    @DisplayName("are actually being found, so neither rule above can pass by matching nothing")
    void shouldFindTheDiagramsItGuards() {
        final List<SequenceDiagramLine> lines = labelLines();
        final long fences = lines.stream()
                .map(line -> line.file() + FENCE_DELIMITER + line.fenceStartLine())
                .distinct()
                .count();

        assertThat(lines)
                .as("no sequence-diagram label lines found under %s — is the working directory the project root?",
                        DOCUMENTATION_DIRECTORY)
                .isNotEmpty();
        assertThat(fences)
                .as("found %d sequence diagrams under %s but expected at least %d. Either diagrams were deleted, or — the reason"
                        + " this assertion exists — the fence walker stopped recognising them and the two rules above are now"
                        + " passing vacuously over an empty list. ADR-006 records the same failure mode for the branch-coverage"
                        + " limit: a guard that can no longer fire must be fixed or deleted, never left green.",
                        fences, DOCUMENTATION_DIRECTORY, MINIMUM_SEQUENCE_DIAGRAMS)
                .isGreaterThanOrEqualTo(MINIMUM_SEQUENCE_DIAGRAMS);
    }

    @Test
    @DisplayName("are screened by an extractor that does catch both characters when they are present")
    void shouldDetectBothCharactersInASyntheticDiagram() {
        final List<String> markdown = List.of(
                MERMAID_FENCE,
                SEQUENCE_DIAGRAM,
                "    autonumber",
                "    A->>B: first clause; second clause",
                "    Note over A: see issue #42",
                "    A->>B: a clean label",
                FENCE_DELIMITER);

        final List<SequenceDiagramLine> lines = labelLines(Path.of("synthetic.md"), markdown);

        assertThat(lines).as("four label lines follow the sequenceDiagram directive").hasSize(4);
        assertThat(lines.stream().filter(line -> line.content().contains(SEMICOLON)).toList())
                .as("the semicolon rule must be able to fire").hasSize(1);
        assertThat(lines.stream().filter(line -> line.content().contains(HASH)).toList())
                .as("the hash rule must be able to fire").hasSize(1);
        assertThat(lines.get(0).lineNumber()).as("line numbers are 1-based and point into the enclosing file").isEqualTo(3);
    }

    @Test
    @DisplayName("are collected from sequence fences only, leaving flowchart labels alone")
    void shouldIgnoreFencesThatAreNotSequenceDiagrams() {
        final List<String> markdown = List.of(
                MERMAID_FENCE,
                "flowchart LR",
                "    NODE[\"finds sessions where expires_at &lt; now; then deletes them\"]",
                FENCE_DELIMITER);

        assertThat(labelLines(Path.of("synthetic.md"), markdown))
                .as("docs/architecture/C4-Diagrams.md carries a flowchart node label containing an escaped angle bracket inside a"
                        + " quoted string. Quoted flowchart labels are a different lexer context that tolerates both characters"
                        + " banned above, and that diagram renders correctly, so widening this test to every Mermaid fence would"
                        + " fail working documentation.")
                .isEmpty();
    }

    /**
     * Every label line of every sequence diagram in the documentation tree.
     */
    private static List<SequenceDiagramLine> labelLines() {
        final List<SequenceDiagramLine> collected = new ArrayList<>();
        for (final Path file : markdownFiles()) {
            collected.addAll(labelLines(file, readLines(file)));
        }
        return collected;
    }

    /**
     * Walks one Markdown file and returns the label lines of each sequence-diagram fence in it.
     *
     * <p>Everything up to and including the {@code sequenceDiagram} directive is dropped, which removes front matter and any
     * leading comment or configuration directive without needing to understand them. {@code %%} lines inside the body are dropped
     * too.
     */
    private static List<SequenceDiagramLine> labelLines(final Path file, final List<String> lines) {
        final List<SequenceDiagramLine> collected = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            if (!MERMAID_FENCE.equals(lines.get(index).strip())) {
                index++;
                continue;
            }
            final int bodyStart = index + 1;
            int bodyEnd = bodyStart;
            while (bodyEnd < lines.size() && !lines.get(bodyEnd).strip().startsWith(FENCE_DELIMITER)) {
                bodyEnd++;
            }
            final int directive = directiveIndex(lines, bodyStart, bodyEnd);
            if (directive >= 0 && isSequenceDiagram(lines.get(directive))) {
                for (int cursor = directive + 1; cursor < bodyEnd; cursor++) {
                    final String content = lines.get(cursor);
                    if (!isSkippable(content)) {
                        collected.add(new SequenceDiagramLine(file, bodyStart + 1, cursor + 1, content));
                    }
                }
            }
            index = bodyEnd + 1;
        }
        return collected;
    }

    /**
     * Index of the first line in {@code [from, to)} that is a diagram directive rather than blank, a comment or front matter, or
     * {@code -1} when the fence holds nothing but those.
     */
    private static int directiveIndex(final List<String> lines, final int from, final int to) {
        boolean inFrontMatter = false;
        for (int cursor = from; cursor < to; cursor++) {
            final String line = lines.get(cursor).strip();
            if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            if (FRONT_MATTER_DELIMITER.equals(line)) {
                inFrontMatter = !inFrontMatter;
                continue;
            }
            if (!inFrontMatter) {
                return cursor;
            }
        }
        return -1;
    }

    /**
     * Whether a diagram directive opens a sequence diagram. Mermaid tolerates trailing content on the directive line.
     */
    private static boolean isSequenceDiagram(final String directive) {
        final String stripped = directive.strip();
        return stripped.equals(SEQUENCE_DIAGRAM) || stripped.startsWith(SEQUENCE_DIAGRAM + " ");
    }

    /**
     * Whether a body line carries no label text. Comment and {@code init} directive lines are skipped because CSS inside a
     * directive legitimately needs semicolons.
     */
    private static boolean isSkippable(final String line) {
        final String stripped = line.strip();
        return stripped.isEmpty() || stripped.startsWith(COMMENT_PREFIX);
    }

    /**
     * Every Markdown file under the documentation tree, in a stable order so failure output is reproducible.
     */
    private static List<Path> markdownFiles() {
        try (Stream<Path> paths = Files.walk(DOCUMENTATION_DIRECTORY)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not walk " + DOCUMENTATION_DIRECTORY, cause);
        }
    }

    /**
     * Reads a documentation file as lines, converting the checked failure into an unchecked one so the tests above read cleanly.
     */
    private static List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * One label-bearing line of one sequence diagram, carrying enough position information to name the offender precisely.
     *
     * @param file           the Markdown file the diagram lives in
     * @param fenceStartLine 1-based line number of the first line inside the fence, used to count diagrams
     * @param lineNumber     1-based line number of this line in {@code file}
     * @param content        the line exactly as written, leading whitespace included
     */
    private record SequenceDiagramLine(Path file, int fenceStartLine, int lineNumber, String content) {

        /**
         * Renders this line as {@code path:line — content}, the form used in assertion output.
         */
        private String describe() {
            return "%s:%d — %s".formatted(file, lineNumber, content.strip());
        }
    }
}
