package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Holds the three module tables in {@code docs/architecture/building-blocks.md} against the dependency block of
 * {@code pom.xml} in both directions (EOP-000).
 *
 * <p>That document exists because of a specific defect class. On Spring Boot 4 autoconfiguration lives in standalone
 * {@code spring-boot-*} modules, so a {@code spring.*} property whose owning module is not a declared dependency is
 * inert: nothing reads it, nothing binds it, nothing warns and nothing fails. Section 2.1 of the document records that
 * this repository was bitten by it twice, under EOP-27 and EOP-33, and the inventory was written so that a future author
 * could check a property's owning module before trusting the property. An inventory is only worth consulting if it is
 * true, and a hand-maintained mirror of {@code pom.xml} decays silently the first time a dependency is added by someone
 * who does not know the table exists. That is the same failure the sibling gates in this package were each written for:
 * {@code AdrIndexConsistencyTest} because an ADR index drifted from the ADRs, {@code EnumMirrorParityTest} because a
 * hand-copied enum list drifted from its enum, and {@code DbSchemaDiagramTest} because a hand-drawn ER diagram drifted
 * from the changelogs that create the tables. This gate is that argument applied to the module inventory.
 *
 * <p>Both directions are checked, and the inverse direction is the one that catches the more embarrassing error. A
 * missing row means a dependency nobody documented, which is a gap. A surplus row means the document asserts the
 * project depends on something it does not, which is worse: a reader who consults the inventory to decide whether a
 * property is live gets a confident wrong answer, which is precisely the outcome the inventory was created to prevent.
 * The same reasoning applies to section 2.4, whose whole content is a claim of absence — so it is checked against the
 * pom too, and against section 2.3, because a module listed as both present and absent is a self-contradiction no
 * single-direction check would see.
 *
 * <p>All three tables share one convention: the first cell of every data row is exactly one backticked artifactId.
 * That is why one extractor and one malformation rule cover all three, and it is why section 3 was restructured under
 * this story — it previously held prose labels such as {@code H2 (runtime)} and {@code Springdoc OpenAPI}, which name
 * no artifact and cannot be compared to anything. The malformation rule is not cosmetic policing: it is what stops the
 * unparseable shape being reintroduced, at which point the comparison would quietly go vacuous rather than fail.
 *
 * <p>The pom is read with the JDK DOM parser and scoped to the direct children of {@code /project/dependencies},
 * <strong>not</strong> with a regular expression over the file text. This is a correctness decision rather than a
 * stylistic one. Four {@code <artifactId>} elements in this repository's own pom are not declared dependencies: the
 * {@code <parent>} coordinates, the project's own artifactId, a {@code commons-text} entry inside
 * {@code <dependencyManagement>} that pins a transitive without declaring it, and a second {@code h2} inside an
 * {@code <exclude>} in the Spring Boot plugin's repackage configuration. A text scan admits all four; a structural walk
 * excludes all four without a single special case, and {@code shouldReadOnlyTheProjectsOwnDependencyBlock} pins that.
 *
 * <p><strong>This is not a dependency audit and must not be described as one.</strong> It compares two files in this
 * repository and nothing else. It does not resolve the dependency tree, so it says nothing about transitives, and it is
 * blind to a dependency that is declared, documented and wrong to depend on at all. It does not read versions, scopes
 * or groupIds — a row may name the right artifact and describe it inaccurately in its other two cells, and this gate
 * will pass. Supply-chain risk is {@code tools/supply-chain/}'s subject, and the vulnerability gate is a separate job.
 *
 * <p>Two further limits, stated rather than glossed. The extractor reads only the first cell of a table row, so a
 * backticked artifactId written in prose, in a heading or in the blockquotes surrounding these tables is invisible to
 * it — that is deliberate, since section 3 documents in a blockquote why {@code commons-text} has no row, and that
 * sentence must not read as a row. And the table walk recognises the shape currently in use: a heading line, a header
 * row, a separator row, then data rows. Rewriting a table as a bullet list or an HTML table would take its rows out of
 * scope, which fails in the safe direction only because {@code shouldFindTheTablesAndTheDependenciesItGuards} asserts
 * a floor on every count; without that floor the rewrite would present as a pass.
 *
 * <p>Surefire runs with the working directory set to the project base directory, so the relative paths resolve.
 */
@DisplayName("the module inventory in docs/architecture/building-blocks.md")
class ModuleInventoryTest {

    /** The project object model, which is the authority on what this project actually depends on. */
    private static final Path POM = Path.of("pom.xml");

    /** The arc42 static-decomposition document whose module tables mirror {@link #POM}. */
    private static final Path BUILDING_BLOCKS = Path.of("docs", "architecture", "building-blocks.md");

    /**
     * Heading prefix of the "modules present" table, matched as a prefix rather than in full on purpose: the real
     * heading carries a re-verification date, so an exact match would fail the build every time that date is refreshed
     * — turning a correct edit into a red build and teaching the next author to weaken the matcher.
     */
    private static final String MODULES_PRESENT_HEADING = "### 2.3";

    /** Heading prefix of the "modules deliberately absent" table, whose whole content is a claim of absence. */
    private static final String MODULES_ABSENT_HEADING = "### 2.4";

    /** Heading prefix of the external-dependency table, restructured under this story to carry artifactIds. */
    private static final String EXTERNAL_DEPENDENCIES_HEADING = "## 3.";

    /**
     * Prefix that separates a Boot autoconfiguration module from any other dependency. The trailing hyphen is
     * load-bearing: {@code springdoc-openapi-starter-webmvc-ui} starts with {@code spring} but not with
     * {@code spring-boot-}, so a shorter prefix would classify a third-party starter as a framework module and look for
     * it in the wrong table.
     */
    private static final String BOOT_MODULE_PREFIX = "spring-boot-";

    /**
     * A first cell holding exactly one backticked artifactId. The character class is Maven's own artifactId alphabet
     * and nothing wider — admitting a space would let {@code `h2` and `postgresql`} through as a single artifact named
     * something no pom can declare, which would then be reported as a surplus row rather than as the malformed row it
     * is. Anything this does not match is collected as a {@link MalformedRow}, so a cell the author wrote in some other
     * shape produces a loud failure instead of a silent omission.
     */
    private static final Pattern ARTIFACT_CELL = Pattern.compile("^`([A-Za-z0-9][A-Za-z0-9._-]*)`$");

    /**
     * The dashed row under a Markdown table header. It is what tells the walker that the header has been passed, so
     * data rows are recognised by position in the table rather than by counting lines from the heading — which keeps
     * the walk correct when a blank line is added between the heading and the table.
     */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[\\s:|-]+\\|$");

    /** Feature that refuses a DOCTYPE outright, which is the single most effective XXE defence available here. */
    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

    /**
     * Floor under the number of Boot modules the pom declares, currently eight and exact.
     *
     * <p>Without it every comparison below passes trivially the moment extraction stops working — an empty set is a
     * subset of everything. That is the vacuous pass ADR-006 records as the branch-coverage lesson: a gate that cannot
     * fail is worse than no gate, because it reports safety. Raise it when a Boot module is added; lower it only
     * alongside a deliberate decision to remove one.
     */
    private static final int MINIMUM_BOOT_MODULES = 8;

    /** Floor under the non-Boot dependencies the pom declares, currently six and exact. See {@link #MINIMUM_BOOT_MODULES}. */
    private static final int MINIMUM_EXTERNAL_DEPENDENCIES = 6;

    /**
     * Floor under the "deliberately absent" table, currently one: {@code spring-boot-h2console}. A floor of one looks
     * trivial but is the only thing standing between an emptied table and a green build, and this is the table whose
     * emptiness is least likely to be noticed by a reader.
     */
    private static final int MINIMUM_ABSENT_MODULES = 1;

    @Test
    @DisplayName("documents every Spring Boot module the pom declares")
    void shouldDocumentEveryBootModuleDeclaredInThePom() {
        final Set<String> declared = bootModules(declaredArtifacts());
        final Set<String> documented = table(MODULES_PRESENT_HEADING).artifacts();

        final Set<String> undocumented = difference(declared, documented);

        assertThat(undocumented)
                .as("%s declares these Spring Boot modules, but the \"modules present\" table in %s does not list them."
                        + " Every declared module activates autoconfiguration, so an undocumented one is a set of"
                        + " properties a reader cannot confirm are live — which is the confusion the inventory exists to"
                        + " remove. Add a row for each, naming the autoconfiguration it activates, and if the module"
                        + " count grew then raise MINIMUM_BOOT_MODULES.", POM, BUILDING_BLOCKS)
                .isEmpty();
    }

    @Test
    @DisplayName("documents no Spring Boot module the pom does not declare")
    void shouldDocumentNoBootModuleThePomDoesNotDeclare() {
        final Set<String> declared = bootModules(declaredArtifacts());
        final Set<String> documented = table(MODULES_PRESENT_HEADING).artifacts();

        final Set<String> surplus = difference(documented, declared);

        assertThat(surplus)
                .as("the \"modules present\" table in %s lists these modules, but %s does not declare them. This is the"
                        + " worse direction: a reader consulting the table to decide whether a spring.* property is"
                        + " bound gets a confident wrong answer, which is exactly the failure the inventory was written"
                        + " to prevent. Either declare the dependency or move the row to the \"deliberately absent\""
                        + " table with the reason for its absence.", BUILDING_BLOCKS, POM)
                .isEmpty();
    }

    @Test
    @DisplayName("documents every external dependency the pom declares")
    void shouldDocumentEveryExternalDependencyDeclaredInThePom() {
        final Set<String> declared = externalDependencies(declaredArtifacts());
        final Set<String> documented = table(EXTERNAL_DEPENDENCIES_HEADING).artifacts();

        final Set<String> undocumented = difference(declared, documented);

        assertThat(undocumented)
                .as("%s declares these non-Boot dependencies, but the external-dependency table in %s does not list"
                        + " them. Add a row for each giving its role, or if one is version-managed rather than declared"
                        + " then it belongs in dependencyManagement and not in the dependency block. If the count grew,"
                        + " raise MINIMUM_EXTERNAL_DEPENDENCIES.", POM, BUILDING_BLOCKS)
                .isEmpty();
    }

    @Test
    @DisplayName("documents no external dependency the pom does not declare")
    void shouldDocumentNoExternalDependencyThePomDoesNotDeclare() {
        final Set<String> declared = externalDependencies(declaredArtifacts());
        final Set<String> documented = table(EXTERNAL_DEPENDENCIES_HEADING).artifacts();

        final Set<String> surplus = difference(documented, declared);

        assertThat(surplus)
                .as("the external-dependency table in %s names these artifacts, but %s does not declare them. A row for"
                        + " a dependency this project does not have is the defect the Resilience4j blockquote in that"
                        + " section records: the table once claimed retry and circuit-breaker support that no"
                        + " dependency provided. Remove the row, or declare the dependency if it was meant to exist."
                        + " Note that an artifact pinned only in dependencyManagement is deliberately not a row.",
                        BUILDING_BLOCKS, POM)
                .isEmpty();
    }

    @Test
    @DisplayName("lists no module as both present and deliberately absent")
    void shouldNotListTheSameModuleAsBothPresentAndAbsent() {
        final Set<String> present = table(MODULES_PRESENT_HEADING).artifacts();
        final Set<String> absent = table(MODULES_ABSENT_HEADING).artifacts();

        final Set<String> contradictory = new LinkedHashSet<>(present);
        contradictory.retainAll(absent);

        assertThat(contradictory)
                .as("%s lists these modules in both the \"modules present\" and \"deliberately absent\" tables. Neither"
                        + " comparison against the pom can catch this on its own, because whichever table happens to"
                        + " agree with the pom makes the other look like an ordinary mismatch. Delete whichever row is"
                        + " no longer true.", BUILDING_BLOCKS)
                .isEmpty();
    }

    @Test
    @DisplayName("declares none of the modules it documents as deliberately absent")
    void shouldDeclareNoneOfTheModulesItDocumentsAsAbsent() {
        final Set<String> absent = table(MODULES_ABSENT_HEADING).artifacts();
        final Set<String> declared = declaredArtifacts();

        final Set<String> contradicted = new LinkedHashSet<>(absent);
        contradicted.retainAll(declared);

        assertThat(contradicted)
                .as("the \"deliberately absent\" table in %s says these modules are not dependencies, but %s declares"
                        + " them. This is the case that most needs a machine: the row carries a security rationale —"
                        + " spring-boot-h2console is absent because it would expose an unauthenticated SQL endpoint —"
                        + " so a module added without reading the table leaves a documented refusal standing over a"
                        + " live dependency. Remove the row and justify the addition, or remove the dependency.",
                        BUILDING_BLOCKS, POM)
                .isEmpty();
    }

    @Test
    @DisplayName("writes every first cell as a single backticked artifactId")
    void shouldWriteEveryFirstCellAsASingleBacktickedArtifactId() {
        final List<MalformedRow> malformed = new ArrayList<>();
        malformed.addAll(table(MODULES_PRESENT_HEADING).malformedRows());
        malformed.addAll(table(MODULES_ABSENT_HEADING).malformedRows());
        malformed.addAll(table(EXTERNAL_DEPENDENCIES_HEADING).malformedRows());

        final List<String> described = malformed.stream().map(MalformedRow::describe).toList();

        assertThat(described)
                .as("these rows in %s do not begin with exactly one backticked artifactId, so this gate cannot compare"
                        + " them to anything. The rule is not cosmetic: section 3 originally held prose labels such as"
                        + " \"H2 (runtime)\" and \"Springdoc OpenAPI\", which name no artifact, and a table of those"
                        + " compares clean against every pom there has ever been. Reducing the comparison to silence is"
                        + " the failure mode, so a cell in any other shape fails loudly instead. Write one artifactId"
                        + " per row and move the prose into the Role cell.", BUILDING_BLOCKS)
                .isEmpty();
    }

    @Test
    @DisplayName("still contains the tables and the dependencies this gate guards")
    void shouldFindTheTablesAndTheDependenciesItGuards() {
        final Set<String> declared = declaredArtifacts();

        assertThat(bootModules(declared))
                .as("expected at least %d Spring Boot modules in %s but found %d. Zero means the dependency walk"
                        + " stopped recognising the block — the pom was restructured, or the parser is no longer"
                        + " scoped where it thinks it is — and every comparison in this class passes vacuously in that"
                        + " state, because an empty set is a subset of everything. A non-zero count below the floor"
                        + " means the module set genuinely shrank, so lower this floor deliberately and say why.",
                        MINIMUM_BOOT_MODULES, POM, bootModules(declared).size())
                .hasSizeGreaterThanOrEqualTo(MINIMUM_BOOT_MODULES);

        assertThat(externalDependencies(declared))
                .as("expected at least %d non-Boot dependencies in %s but found %d. See the reasoning on the previous"
                        + " assertion: this floor exists so that a broken extraction fails instead of reporting safety.",
                        MINIMUM_EXTERNAL_DEPENDENCIES, POM, externalDependencies(declared).size())
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXTERNAL_DEPENDENCIES);

        assertThat(table(MODULES_PRESENT_HEADING).artifacts())
                .as("found no rows under a heading starting \"%s\" in %s. Either the heading was renamed, or the table"
                        + " was rewritten in a shape the walker does not recognise — a bullet list or raw HTML — and"
                        + " with no rows to compare, both directions of the modules-present check pass on nothing.",
                        MODULES_PRESENT_HEADING, BUILDING_BLOCKS)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_BOOT_MODULES);

        assertThat(table(EXTERNAL_DEPENDENCIES_HEADING).artifacts())
                .as("found fewer than %d rows under a heading starting \"%s\" in %s. See above: an unrecognised table"
                        + " shape silently empties this comparison rather than failing it.",
                        MINIMUM_EXTERNAL_DEPENDENCIES, EXTERNAL_DEPENDENCIES_HEADING, BUILDING_BLOCKS)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXTERNAL_DEPENDENCIES);

        assertThat(table(MODULES_ABSENT_HEADING).artifacts())
                .as("found no rows under a heading starting \"%s\" in %s. That table is the one carrying a security"
                        + " rationale for a module's absence, so an emptied version of it removes a documented refusal"
                        + " while leaving this gate green.", MODULES_ABSENT_HEADING, BUILDING_BLOCKS)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_ABSENT_MODULES);
    }

    @Test
    @DisplayName("has a table walk that reports each malformation in a synthetic inventory")
    void shouldDetectEachMalformationInASyntheticInventory() {
        final List<String> document = List.of(
                "## 3. Key External Dependencies",
                "",
                "| Artifact | Role | Notes |",
                "|---|---|---|",
                "| `liquibase-core` | Migration engine | Changelogs under db/changelog |",
                "| Springdoc OpenAPI | API documentation | a prose label, naming no artifact |",
                "| `h2` and `postgresql` | Two artifacts in one cell | must be one row each |",
                "",
                "## 4. Runtime View",
                "",
                "| Artifact | Role | Notes |",
                "|---|---|---|",
                "| `spring-boot-starter-web` | Belongs to another section | must not be collected |");

        final InventoryTable parsed = table(document, EXTERNAL_DEPENDENCIES_HEADING);

        assertThat(parsed.artifacts())
                .as("the walk must collect the well-formed row and stop at the following heading, so the row under"
                        + " \"## 4.\" must not leak into this table's artifact set")
                .containsExactly("liquibase-core");
        assertThat(parsed.malformedRows().stream().map(MalformedRow::describe))
                .as("the prose-label rule and the two-artifacts-in-one-cell rule must both be able to fire, and the"
                        + " header row above the separator must not be mistaken for a malformed data row")
                .hasSize(2);
        assertThat(parsed.malformedRows().stream().map(MalformedRow::lineNumber))
                .as("a malformed row must be reported at its real line number in the file, counted from one, so that"
                        + " the failure message points an author at the line to edit")
                .containsExactly(6, 7);
    }

    /**
     * Pins the structural scoping of the pom walk against every decoy this repository's own pom actually contains.
     *
     * <p>Four {@code <artifactId>} elements in {@code pom.xml} are not declared dependencies, and all four are
     * reproduced in the fixture below: the {@code <parent>} coordinates, the project's own artifactId, a
     * {@code commons-text} entry inside {@code <dependencyManagement>} that pins a transitive version without
     * declaring the dependency, and a second {@code h2} inside an {@code <exclude>} in the Spring Boot plugin's
     * repackage configuration, which keeps the H2 driver out of the shipped jar. A regular expression over the file
     * text admits all four, and each would then be reported as a surplus row against the document — three false
     * failures and, in {@code commons-text}'s case, pressure to add a row that
     * {@code shouldDocumentNoExternalDependencyThePomDoesNotDeclare} would then reject. Scoping to the direct children
     * of {@code /project/dependencies} excludes all four with no special-casing, and excludes a plugin-level
     * {@code <dependencies>} block by the same rule, which is why the fixture carries one of those too.
     */
    @Test
    @DisplayName("has a pom walk that reads only the project's own dependency block")
    void shouldReadOnlyTheProjectsOwnDependencyBlock() {
        final String decoyPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                    </parent>
                    <artifactId>ElevationOfPrivilegeEoP</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-text</artifactId>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <dependencies>
                                    <dependency>
                                        <artifactId>a-plugin-dependency</artifactId>
                                    </dependency>
                                </dependencies>
                                <executions>
                                    <execution>
                                        <configuration>
                                            <excludes>
                                                <exclude>
                                                    <artifactId>h2</artifactId>
                                                </exclude>
                                            </excludes>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        final Set<String> declared = declaredArtifacts(decoyPom, "a synthetic pom carrying every decoy");

        assertThat(declared)
                .as("only /project/dependencies/dependency/artifactId is a declared dependency. The parent"
                        + " coordinates, the project's own artifactId, a dependencyManagement pin, a plugin's own"
                        + " dependency and an artifactId inside a plugin <exclude> must all be excluded — every one of"
                        + " those shapes is live in this repository's pom, and a text scan would admit all of them.")
                .containsExactly("spring-boot-starter-web");
    }

    /**
     * Reads the artifactIds this project declares as dependencies.
     *
     * @return the declared artifactIds in file order, never {@code null}
     */
    private static Set<String> declaredArtifacts() {
        return declaredArtifacts(readString(POM), POM.toString());
    }

    /**
     * Reads the artifactIds declared as direct children of {@code /project/dependencies} in the given pom.
     *
     * @param xml         the pom document text
     * @param description what the text is, used in failure messages
     * @return the declared artifactIds in document order, never {@code null}
     */
    private static Set<String> declaredArtifacts(final String xml, final String description) {
        final Element project = parseXml(xml, description).getDocumentElement();
        final Set<String> artifacts = new LinkedHashSet<>();
        for (final Element dependencies : childElements(project, "dependencies")) {
            for (final Element dependency : childElements(dependencies, "dependency")) {
                for (final Element artifactId : childElements(dependency, "artifactId")) {
                    artifacts.add(artifactId.getTextContent().strip());
                }
            }
        }
        return artifacts;
    }

    /**
     * Selects the Spring Boot autoconfiguration modules from a set of artifactIds.
     *
     * @param artifacts artifactIds to filter
     * @return those beginning {@link #BOOT_MODULE_PREFIX}, preserving order
     */
    private static Set<String> bootModules(final Set<String> artifacts) {
        return artifacts.stream()
                .filter(artifact -> artifact.startsWith(BOOT_MODULE_PREFIX))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /**
     * Selects the non-Boot dependencies from a set of artifactIds.
     *
     * @param artifacts artifactIds to filter
     * @return those not beginning {@link #BOOT_MODULE_PREFIX}, preserving order
     */
    private static Set<String> externalDependencies(final Set<String> artifacts) {
        return artifacts.stream()
                .filter(artifact -> !artifact.startsWith(BOOT_MODULE_PREFIX))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /**
     * Computes {@code left} minus {@code right} in a set that preserves insertion order, so failure output is
     * reproducible from run to run rather than varying with hash order.
     *
     * @param left  the set to subtract from
     * @param right the set to subtract
     * @return the members of {@code left} absent from {@code right}
     */
    private static Set<String> difference(final Set<String> left, final Set<String> right) {
        final Set<String> remainder = new LinkedHashSet<>(left);
        remainder.removeAll(right);
        return remainder;
    }

    /**
     * Extracts the table under the given heading from the building-blocks document.
     *
     * @param headingPrefix the prefix the section heading starts with
     * @return the artifactIds and malformed rows of that table
     */
    private static InventoryTable table(final String headingPrefix) {
        return table(readLines(BUILDING_BLOCKS), headingPrefix);
    }

    /**
     * Extracts the table under the given heading from the given document lines.
     *
     * <p>Data rows are recognised by position rather than by counting: the header row is whatever precedes the dashed
     * separator, and only rows after it are read. The section ends at the next line starting with {@code #}, so a row
     * belonging to a later section is never attributed to this one.
     *
     * @param lines         the whole document, one entry per line
     * @param headingPrefix the prefix the section heading starts with
     * @return the artifactIds and malformed rows of that table
     */
    private static InventoryTable table(final List<String> lines, final String headingPrefix) {
        final Set<String> artifacts = new LinkedHashSet<>();
        final List<MalformedRow> malformedRows = new ArrayList<>();
        boolean inSection = false;
        boolean pastSeparator = false;

        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index).strip();
            if (line.startsWith("#")) {
                if (inSection) {
                    break;
                }
                inSection = line.startsWith(headingPrefix);
                continue;
            }
            if (!inSection || !line.startsWith("|")) {
                continue;
            }
            if (TABLE_SEPARATOR.matcher(line).matches()) {
                pastSeparator = true;
                continue;
            }
            if (!pastSeparator) {
                continue;
            }
            final String cell = firstCell(line);
            final Matcher matcher = ARTIFACT_CELL.matcher(cell);
            if (matcher.matches()) {
                artifacts.add(matcher.group(1));
            } else {
                malformedRows.add(new MalformedRow(index + 1, cell));
            }
        }
        return new InventoryTable(artifacts, malformedRows);
    }

    /**
     * Reads the first cell of a Markdown table row.
     *
     * @param row a line beginning with a pipe
     * @return the stripped content of the first cell, or an empty string if the row has none
     */
    private static String firstCell(final String row) {
        final String[] cells = row.split("\\|", -1);
        return cells.length > 1 ? cells[1].strip() : "";
    }

    /**
     * Parses XML with external entity resolution disabled.
     *
     * <p>The parser is deliberately left namespace-unaware, which is the factory default, so element names read back
     * bare despite the pom declaring a default namespace. Making it namespace-aware would require every lookup below
     * to carry the Maven POM namespace URI, and forgetting it once would silently return no elements — a vacuous pass.
     *
     * @param xml         the document text
     * @param description what the text is, used in failure messages
     * @return the parsed document
     */
    private static Document parseXml(final String xml, final String description) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setExpandEntityReferences(false);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (final ParserConfigurationException | SAXException cause) {
            throw new IllegalStateException("could not parse " + description, cause);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + description, cause);
        }
    }

    /**
     * Selects the direct child elements of an element by name, ignoring text and comment nodes.
     *
     * <p>Directness is the whole mechanism: {@code dependencyManagement/dependencies} is a grandchild of
     * {@code project}, and a plugin's {@code dependencies} is deeper still, so neither is ever reached.
     *
     * @param parent the element to look under
     * @param name   the child element name to match
     * @return the matching direct children in document order
     */
    private static List<Element> childElements(final Element parent, final String name) {
        final List<Element> matches = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            final Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                matches.add((Element) child);
            }
        }
        return matches;
    }

    /**
     * Reads a repository file as text.
     *
     * @param file the file to read, relative to the project base directory
     * @return its contents
     */
    private static String readString(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * Reads a repository file as lines.
     *
     * @param file the file to read, relative to the project base directory
     * @return its lines, without terminators
     */
    private static List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * One extracted inventory table.
     *
     * @param artifacts     the artifactIds named in the first cell of each well-formed data row
     * @param malformedRows the data rows whose first cell was not a single backticked artifactId
     */
    private record InventoryTable(Set<String> artifacts, List<MalformedRow> malformedRows) {
    }

    /**
     * A data row this gate could not read as an artifactId.
     *
     * @param lineNumber the one-based line number in the document
     * @param text       the first cell as written
     */
    private record MalformedRow(int lineNumber, String text) {

        /**
         * Renders the row for assertion output.
         *
         * @return the line number and the offending cell
         */
        String describe() {
            return "line %d: %s".formatted(lineNumber, text);
        }
    }
}
