package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the ER diagram in {@code docs/architecture/db-schema.md} to the tables the Liquibase changelogs actually create (EOP-167).
 *
 * <p>The diagram is hand-derived from {@code src/main/resources/db/changelog/changes/}, because ADR-008 makes Liquibase the sole
 * source of schema truth and there is no live database to introspect at build time. A hand-derived mirror of a machine-readable
 * artefact is precisely the shape this repository has repeatedly chosen to gate rather than trust: {@code AdrIndexConsistencyTest}
 * exists because ADR status lines drift, {@code EnumMirrorParityTest} because enum member lists drift, and
 * {@code DeckArithmeticClaimsTest} because arithmetic in prose drifts. Without this test a twelfth changelog could add a table and
 * leave the diagram quietly wrong through every green build, which is the failure mode {@code MermaidSequenceTextTest} was written
 * for after four broken diagrams survived months of green builds unnoticed.
 *
 * <p>Two axes are checked, in both directions. A table the changelogs create but the diagram omits is the drift this test was
 * written to catch. A table the diagram declares but no changelog creates is the rarer inverse — a typo, or an entity left behind
 * after a rename — and it matters because a misspelled entity name still renders, so the reader sees a confident picture of a table
 * that does not exist.
 *
 * <p>It also gives the {@code erDiagram} fence the only structural check anything in the build performs on it. Nothing here parses
 * Mermaid: {@code MermaidSequenceTextTest} is scoped to {@code sequenceDiagram} fences by construction, no Mermaid package exists
 * anywhere in the tree, and CI does not render diagrams. So the two structural rules below — every entity block closes, and every
 * relationship names a declared entity — are chosen as the two malformations that both break rendering and are mechanically
 * detectable by reading text. <strong>This is not a syntax check and must not be described as one.</strong> An {@code erDiagram}
 * carrying a malformed attribute line, an invalid cardinality token or a stray character can still fail to render and will still
 * pass {@code ./mvnw verify}. What this test proves is narrower and worth stating exactly: the entity names agree with the schema,
 * and the relationships join entities that exist.
 *
 * <p>Two further limits, stated rather than glossed. Column-level accuracy is not checked — types, nullability, and the PK/UK/FK
 * markers are read by a reviewer, and parsing them would mean reimplementing Mermaid's entity grammar for a benefit that does not
 * justify it. And table extraction reads {@code createTable} only, which is correct today because every {@code dropTable} in the
 * changelog tree sits inside a {@code <rollback>} block as the inverse of a create, and no table has ever genuinely been dropped;
 * were one dropped in a future changelog, its {@code createTable} would remain in history and this test would keep accepting a
 * stale entity. Close that hole when the first real drop lands, not before.
 *
 * <p>Extraction has one further blind spot worth naming, because a reader could otherwise assume the changelog side is exhaustive.
 * A table created by a raw {@code <sql>} or {@code <sqlFile>} changeset carries no {@code createTable} element and is therefore
 * invisible here, so the diagram could omit it and still pass. No changeset in this tree creates a table that way — raw
 * {@code <sql>} is used only for check constraints and indexes, because Liquibase's own {@code addCheckConstraint} is a Pro
 * feature — so the gap is latent rather than open. It fails in the safe direction if it ever opens: a table would go unchecked,
 * never falsely reported.
 *
 * <p>Surefire runs with the working directory set to the project base directory, so the relative paths resolve.
 */
@DisplayName("the ER diagram in docs/architecture/db-schema.md")
class DbSchemaDiagramTest {

    /**
     * The hand-authored ER diagram this test guards. Relative to the project base directory.
     */
    private static final Path DIAGRAM = Path.of("docs", "architecture", "db-schema.md");

    /**
     * The Liquibase changelog directory, which is the authority the diagram is checked against.
     */
    private static final Path CHANGELOG_DIRECTORY = Path.of("src", "main", "resources", "db", "changelog", "changes");

    /**
     * Opening delimiter of a Mermaid code fence, matched after stripping leading whitespace.
     */
    private static final String MERMAID_FENCE = "```mermaid";

    /**
     * Any fence delimiter. A fence closes on the first following line that starts with three backticks.
     */
    private static final String FENCE_DELIMITER = "```";

    /**
     * The directive that identifies a fence as an entity-relationship diagram.
     */
    private static final String ER_DIAGRAM = "erDiagram";

    /**
     * Mermaid's comment prefix, which also introduces an {@code %%{init: …}%%} configuration directive.
     */
    private static final String COMMENT_PREFIX = "%%";

    /**
     * Matches the {@code tableName} attribute of a {@code createTable} element, tolerating any attribute order and a tag broken
     * across lines.
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "<createTable\\b[^>]*?tableName\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /**
     * Matches an entity block opening: a table name followed by an opening brace, as in {@code game_session} and a brace.
     *
     * <p>The identifier class admits upper case deliberately, even though every table in this schema is lower case. A pattern
     * restricted to {@code [a-z_]} would not match a mixed-case name such as {@code Game_Session} followed by a brace, and would
     * therefore drop the line silently, leaving the entity undeclared and the diagram apparently clean. Admitting it means the name
     * is captured and then fails the checks below against the lower-case name the changelog actually creates — a loud failure
     * instead of a blind spot.
     */
    private static final Pattern ENTITY_DECLARATION = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\{$");

    /**
     * Matches a relationship line, capturing the entity either side of the cardinality token. Applied only outside an entity
     * block, so a column line can never be mistaken for one.
     *
     * <p>Both endpoint classes admit upper case for the same reason as {@code ENTITY_DECLARATION}, and here the consequence was
     * demonstrated rather than assumed: while these classes were {@code [a-z_][a-z0-9_]*}, renaming an endpoint to
     * {@code trick_play_componentX} on a relationship line alone left the line unmatched and silently discarded, and the whole
     * build stayed green. Capturing the endpoint is what lets {@code shouldOnlyRelateEntitiesItDeclares} report it as dangling.
     */
    private static final Pattern RELATIONSHIP =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s+(\\S+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:.*$");

    /**
     * Closing delimiter of an entity block.
     */
    private static final String ENTITY_BLOCK_CLOSE = "}";

    /**
     * A floor on the number of entities found, so none of the rules below can pass by quietly matching nothing — the failure mode
     * ADR-006 records for the branch-coverage limit, and the reason {@code MermaidSequenceTextTest} carries the same guard.
     *
     * <p>Ten is the count at the time of writing, and it is the whole schema. Raise it when a table is added; lower it only
     * alongside a deliberate decision to drop one.
     */
    private static final int MINIMUM_ENTITIES = 10;

    @Test
    @DisplayName("declares an entity for every table the Liquibase changelogs create")
    void shouldDeclareAnEntityForEveryTableTheChangelogsCreate() {
        final Set<String> missing = new LinkedHashSet<>(changelogTables());
        missing.removeAll(parseDiagram().entities());

        assertThat(missing)
                .as("these tables are created by a changelog in %s but have no entity block in %s. The diagram is hand-derived"
                        + " from the changelogs, so adding a table without updating it leaves the only complete picture of the"
                        + " schema quietly wrong — and wrong in the direction a reader cannot detect, because a diagram that is"
                        + " merely incomplete still renders and still looks authoritative. Add the entity with its columns and"
                        + " its ON DELETE-annotated relationships, then raise MINIMUM_ENTITIES.",
                        CHANGELOG_DIRECTORY, DIAGRAM)
                .isEmpty();
    }

    @Test
    @DisplayName("declares no entity that no changelog ever creates")
    void shouldNotDeclareAnEntityThatNoChangelogCreates() {
        final Set<String> surplus = new LinkedHashSet<>(parseDiagram().entities());
        surplus.removeAll(changelogTables());

        assertThat(surplus)
                .as("these entity blocks in %s name tables that no createTable in %s produces. A misspelled or renamed entity"
                        + " renders perfectly, so the reader is shown a table that does not exist — the inverse of the omission"
                        + " rule beside this one, and harder to spot by eye precisely because nothing looks broken.",
                        DIAGRAM, CHANGELOG_DIRECTORY)
                .isEmpty();
    }

    @Test
    @DisplayName("relates only entities it has declared, so no relationship dangles")
    void shouldOnlyRelateEntitiesItDeclares() {
        final ErDiagram diagram = parseDiagram();
        final List<String> dangling = diagram.relationships().stream()
                .filter(relationship -> !diagram.entities().contains(relationship.left())
                        || !diagram.entities().contains(relationship.right()))
                .map(Relationship::describe)
                .toList();

        assertThat(dangling)
                .as("these relationship lines in %s name an entity with no block declaring it. Mermaid invents an empty box for"
                        + " an undeclared entity rather than failing, so a typo on one side of a cardinality token yields a"
                        + " diagram that renders with a phantom table in it. This is one of only two structural checks anything"
                        + " in the build performs on an erDiagram fence — MermaidSequenceTextTest is scoped to sequenceDiagram"
                        + " fences and does not see this file.", DIAGRAM)
                .isEmpty();
    }

    @Test
    @DisplayName("closes every entity block it opens")
    void shouldCloseEveryEntityBlockItOpens() {
        assertThat(parseDiagram().unclosedEntities())
                .as("these entity blocks in %s are opened with a brace that the fence never closes. An unbalanced brace stops"
                        + " the whole diagram rendering, and because nothing in this build parses Mermaid the page simply shows"
                        + " a raw code block to any reader who opens it.", DIAGRAM)
                .isEmpty();
    }

    @Test
    @DisplayName("is actually being found and parsed, so none of the rules above can pass vacuously")
    void shouldFindTheDiagramItGuards() {
        final ErDiagram diagram = parseDiagram();

        assertThat(diagram.entities())
                .as("expected at least %d erDiagram entity blocks in %s. A count of zero means the diagram was deleted or the"
                        + " fence walker stopped recognising it, and every rule above is now passing over an empty list; a count"
                        + " between one and %d means the diagram shrank, so lower this floor deliberately or restore what was"
                        + " removed. ADR-006 records this exact failure mode for the branch-coverage limit: a guard that can no"
                        + " longer fire must be fixed or deleted, never left green.",
                        MINIMUM_ENTITIES, DIAGRAM, MINIMUM_ENTITIES - 1)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_ENTITIES);
        assertThat(diagram.relationships())
                .as("no relationship lines found in %s, so the dangling-endpoint rule is vacuous", DIAGRAM)
                .isNotEmpty();
        assertThat(changelogTables())
                .as("expected at least %d createTable elements under %s. Zero means the changelogs were not found at all, so"
                        + " check the working directory is the project root; fewer than %d means the extraction regex stopped"
                        + " matching and the comparison rules above are now measuring against a short list.",
                        MINIMUM_ENTITIES, CHANGELOG_DIRECTORY, MINIMUM_ENTITIES)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_ENTITIES);
    }

    @Test
    @DisplayName("is screened by a parser that does catch an omission, a dangling endpoint and an unclosed block")
    void shouldDetectEachMalformationInASyntheticDiagram() {
        final List<String> markdown = List.of(
                MERMAID_FENCE,
                ER_DIAGRAM,
                "    %% a comment, which is skipped",
                "    card {",
                "        UUID id PK \"identifier\"",
                "    }",
                "    game_session {",
                "        UUID id PK \"identifier\"",
                "    hand {",
                "    }",
                "    card ||--o{ absent_table : \"dangles\"",
                FENCE_DELIMITER);

        final ErDiagram diagram = parse(markdown);

        assertThat(diagram.entities()).as("three entity blocks are opened").containsExactly("card", "game_session", "hand");
        assertThat(diagram.unclosedEntities()).as("the unclosed-block rule must be able to fire").containsExactly("game_session");
        assertThat(diagram.relationships()).as("one relationship line sits outside every entity block").hasSize(1);
        assertThat(diagram.relationships().getFirst().right())
                .as("the dangling-endpoint rule must be able to fire").isEqualTo("absent_table");
        assertThat(diagram.entities()).as("the omission rule must be able to fire").doesNotContain("trick_play");
    }

    /**
     * Regression test for the hole a review found in the first cut of this gate.
     *
     * <p>Both identifier patterns originally admitted lower case only. A relationship endpoint carrying any other character
     * therefore failed to match at all, and an unmatched line is discarded rather than reported — so renaming an endpoint to
     * {@code trick_play_componentX} on a relationship line alone left the diagram naming a table that does not exist and the
     * whole build green. Widening the classes to admit upper case does not add a rule; it lets the existing dangling-endpoint
     * rule see the line, which is the difference between a silent drop and a failure.
     */
    @Test
    @DisplayName("parses a mixed-case endpoint so the dangling-endpoint rule can fail on it rather than dropping the line")
    void shouldParseRatherThanDiscardAMixedCaseRelationshipEndpoint() {
        final List<String> markdown = List.of(
                MERMAID_FENCE,
                ER_DIAGRAM,
                "    trick_play {",
                "        UUID id PK \"identifier\"",
                "    }",
                "    trick_play_component {",
                "        UUID trick_play_id PK \"parent play\"",
                "    }",
                "    trick_play ||--o{ trick_play_componentX : \"components (CASCADE)\"",
                FENCE_DELIMITER);

        final ErDiagram diagram = parse(markdown);

        assertThat(diagram.relationships())
                .as("the mixed-case line must be parsed, not silently discarded — discarding it is what let the build stay green")
                .hasSize(1);
        assertThat(diagram.relationships().getFirst().right())
                .as("the endpoint is captured verbatim so the failure names what the diagram actually says")
                .isEqualTo("trick_play_componentX");
        assertThat(diagram.entities())
                .as("no block declares that endpoint, so the dangling-endpoint rule has something to report")
                .doesNotContain("trick_play_componentX");
    }

    /**
     * Every table name created by a {@code createTable} element anywhere in the changelog directory.
     *
     * <p>A {@link LinkedHashSet} keyed on insertion order over sorted files, so failure output is reproducible.
     *
     * @return the distinct table names the changelogs create
     */
    private static Set<String> changelogTables() {
        final Set<String> tables = new LinkedHashSet<>();
        for (final Path file : changelogFiles()) {
            final Matcher matcher = CREATE_TABLE.matcher(readString(file));
            while (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }
        return tables;
    }

    /**
     * Every changelog file, in a stable order so failure output is reproducible.
     *
     * @return the changelog XML files, sorted by path
     */
    private static List<Path> changelogFiles() {
        try (Stream<Path> paths = Files.walk(CHANGELOG_DIRECTORY)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not walk " + CHANGELOG_DIRECTORY, cause);
        }
    }

    /**
     * Parses the ER diagram out of the documentation file this test guards.
     *
     * @return the first {@code erDiagram} fence in {@link #DIAGRAM}, or an empty diagram when there is none
     */
    private static ErDiagram parseDiagram() {
        return parse(readLines(DIAGRAM));
    }

    /**
     * Parses the first {@code erDiagram} fence out of a Markdown document.
     *
     * <p>Entity blocks and relationships are distinguished by nesting rather than by shape: a relationship is only recognised
     * outside a block, which is what stops a column line being read as one.
     *
     * @param lines the Markdown document, as lines
     * @return the entities, the entities whose blocks never close, and the relationships between them
     */
    private static ErDiagram parse(final List<String> lines) {
        final List<String> entities = new ArrayList<>();
        final List<String> unclosed = new ArrayList<>();
        final List<Relationship> relationships = new ArrayList<>();

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
            if (isErDiagram(lines, bodyStart, bodyEnd)) {
                readBody(lines, bodyStart, bodyEnd, entities, unclosed, relationships);
                return new ErDiagram(entities, unclosed, relationships);
            }
            index = bodyEnd + 1;
        }
        return new ErDiagram(entities, unclosed, relationships);
    }

    /**
     * Reads the body of an {@code erDiagram} fence, collecting entity names, unclosed blocks and relationships.
     *
     * @param lines         the enclosing document
     * @param bodyStart     index of the first line inside the fence
     * @param bodyEnd       index one past the last line inside the fence
     * @param entities      collects every entity block opened, in document order
     * @param unclosed      collects every entity whose block is never closed
     * @param relationships collects every relationship line found outside a block
     */
    private static void readBody(final List<String> lines, final int bodyStart, final int bodyEnd,
            final List<String> entities, final List<String> unclosed, final List<Relationship> relationships) {
        String open = null;
        for (int cursor = bodyStart; cursor < bodyEnd; cursor++) {
            final String line = lines.get(cursor).strip();
            if (line.isEmpty() || line.startsWith(COMMENT_PREFIX) || ER_DIAGRAM.equals(line)) {
                continue;
            }
            final Matcher declaration = ENTITY_DECLARATION.matcher(line);
            if (declaration.matches()) {
                if (open != null) {
                    unclosed.add(open);
                }
                open = declaration.group(1);
                entities.add(open);
                continue;
            }
            if (ENTITY_BLOCK_CLOSE.equals(line)) {
                open = null;
                continue;
            }
            if (open != null) {
                continue;
            }
            final Matcher relationship = RELATIONSHIP.matcher(line);
            if (relationship.matches()) {
                relationships.add(new Relationship(relationship.group(1), relationship.group(3), cursor + 1));
            }
        }
        if (open != null) {
            unclosed.add(open);
        }
    }

    /**
     * Whether the fence spanning {@code [bodyStart, bodyEnd)} opens an ER diagram, ignoring blank and comment lines before the
     * directive.
     *
     * @param lines     the enclosing document
     * @param bodyStart index of the first line inside the fence
     * @param bodyEnd   index one past the last line inside the fence
     * @return {@code true} when the first meaningful line is the {@code erDiagram} directive
     */
    private static boolean isErDiagram(final List<String> lines, final int bodyStart, final int bodyEnd) {
        for (int cursor = bodyStart; cursor < bodyEnd; cursor++) {
            final String line = lines.get(cursor).strip();
            if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            return ER_DIAGRAM.equals(line) || line.startsWith(ER_DIAGRAM + " ");
        }
        return false;
    }

    /**
     * Reads a file as a single string, converting the checked failure into an unchecked one.
     *
     * @param file the file to read
     * @return the file content
     */
    private static String readString(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * Reads a file as lines, converting the checked failure into an unchecked one.
     *
     * @param file the file to read
     * @return the file content, as lines
     */
    private static List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * One parsed {@code erDiagram} fence.
     *
     * @param entities         every entity block opened, in document order
     * @param unclosedEntities every entity whose block is never closed
     * @param relationships    every relationship line found outside an entity block
     */
    private record ErDiagram(List<String> entities, List<String> unclosedEntities, List<Relationship> relationships) {
    }

    /**
     * One relationship line, carrying the entity either side of the cardinality token.
     *
     * @param left       the entity named before the cardinality token
     * @param right      the entity named after it
     * @param lineNumber 1-based line number of this line in the enclosing file
     */
    private record Relationship(String left, String right, int lineNumber) {

        /**
         * Renders this relationship as {@code line N: left — right}, the form used in assertion output.
         *
         * @return a human-readable description naming the offending line
         */
        private String describe() {
            return "line %d: %s — %s".formatted(lineNumber, left, right);
        }
    }
}
