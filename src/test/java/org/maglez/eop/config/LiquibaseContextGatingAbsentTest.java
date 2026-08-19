package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Enforces ADR-043: Liquibase context and label gating is not used in this repository.
 *
 * <p>ADR-043 records that a changeset tagged {@code context="prod"} does <em>not</em> restrict
 * itself to prod. With {@code spring.liquibase.contexts} unset,
 * {@code new ContextExpression("prod").matches(new Contexts())} returns {@code true} — the tag is
 * inert and the changeset runs in every environment. The failure is therefore <strong>fail-open</strong>,
 * and it reads as a restriction while enforcing nothing. Across the 21 YAML encodings tabulated in
 * that ADR, 13 leak, and the leaking set is exactly the set whose effective filter names nothing:
 * absent, empty, blank, {@code []}, {@code {}}, {@code null} and {@code ~} are all equivalent to
 * unset, discarded by one of three independent mechanisms in Boot and Liquibase.
 *
 * <p>The reason that decision needs a test rather than only a rule and an ADR is the combination
 * of three properties, any two of which would be survivable. The breach is <em>silent</em>: nothing
 * logs a warning for a context filter that matches nothing. It is <em>fail-open</em>: the migration
 * runs anyway, so no environment errors out to reveal the mistake. And it is <em>invisible at
 * startup</em>, because {@code ddl-auto: validate} is not the backstop it appears to be — it walks
 * the mapped Hibernate metamodel only, as {@code MappedSchemaValidationIntegrationTest} concedes in
 * its own Javadoc, so a skipped changeset that adds a unique constraint or a foreign key passes
 * validation without complaint. EOP-14 relies on exactly those constraints to make seat
 * impersonation structurally unrepresentable, which is what turns a convention breach here into a
 * security regression nobody observes. Before this class, all three could happen with a green build.
 *
 * <p>The assertions are deliberately structural rather than prose-matching. They check for the
 * presence of specific XML attributes and specific configuration keys, so they cannot rot into the
 * phrase-list guards that {@code build-quality.md} warns about — a guard whose matcher is a list of
 * spellings proves only that those spellings are absent, whereas an attribute either is or is not
 * present on a changeset. Equally, they are not guards that can become unfireable: they stay firing
 * for as long as Liquibase supports the attributes.
 *
 * <p>{@code labels} and {@code label-filter} are covered alongside {@code context} on purpose.
 * Labels are Liquibase's sibling gating mechanism and fail open in precisely the same shape —
 * {@code labelFilter} is guarded by the same {@code CollectionUtils.isEmpty} idiom in the same
 * autoconfiguration method — so the natural reaction to learning that contexts cannot be trusted is
 * to reach for labels and land in the identical hole. ADR-043 scopes its decision to both.
 *
 * <p>If this repository ever genuinely needs environment-restricted migrations, this test is the
 * thing to delete, and deleting it should be the visible, reviewed part of that change. ADR-043
 * must be amended or superseded in the same commit, and the amendment must explain how both halves
 * of the pair — the tag on the changeset <em>and</em> a real, non-empty context named in
 * <em>every</em> profile, above all the one that must not run the migration — are guaranteed to stay
 * in step. Do not weaken the assertions to accommodate one changeset.
 */
@DisplayName("ADR-043: Liquibase context and label gating is absent")
class LiquibaseContextGatingAbsentTest {

    private static final Path CHANGELOG_DIRECTORY = Path.of("src/main/resources/db/changelog");

    /** Matches a {@code context=} or {@code contexts=} XML attribute, whatever the surrounding whitespace. */
    private static final Pattern CONTEXT_ATTRIBUTE = Pattern.compile("\\bcontexts?\\s*=");

    /** Matches a {@code labels=} XML attribute. Liquibase spells the changeset-side attribute plural. */
    private static final Pattern LABELS_ATTRIBUTE = Pattern.compile("\\blabels\\s*=");

    @Test
    @DisplayName("no changeset carries a context attribute, because a bare context restricts nothing")
    void shouldFindNoContextAttributeOnAnyChangeset() {
        assertThat(attributeOccurrences(CONTEXT_ATTRIBUTE))
                .as("A context attribute is inert unless spring.liquibase.contexts names a real, "
                        + "non-empty context in every profile — see ADR-043. Found occurrences")
                .isEmpty();
    }

    @Test
    @DisplayName("no changeset carries a labels attribute, the same trap under a different name")
    void shouldFindNoLabelsAttributeOnAnyChangeset() {
        assertThat(attributeOccurrences(LABELS_ATTRIBUTE))
                .as("labelFilter fails open in the same shape as contexts — see ADR-043. Found occurrences")
                .isEmpty();
    }

    @Test
    @DisplayName("the default profile sets neither spring.liquibase.contexts nor label-filter")
    void shouldLeaveGatingUnsetInTheDefaultProfile() {
        assertGatingUnsetIn("application.yml");
    }

    @Test
    @DisplayName("the prod profile sets neither spring.liquibase.contexts nor label-filter")
    void shouldLeaveGatingUnsetInTheProdProfile() {
        assertGatingUnsetIn("application-prod.yml");
    }

    @Test
    @DisplayName("the changelog directory was actually scanned, so the absence assertions mean something")
    void shouldHaveScannedTheChangelogFiles() {
        assertThat(changelogFiles())
                .as("If this is empty the two absence assertions above are vacuous and this test is lying")
                .hasSizeGreaterThan(1);
    }

    /**
     * Asserts that a profile's YAML sets neither gating key. Reads the file through
     * {@link YamlPropertiesFactoryBean} — the same flattening Spring itself applies — so a key written
     * in nested or dotted form is caught either way, and a value of any shape is caught rather than only
     * a non-empty one. An empty value is the dangerous case, not the safe one.
     */
    private void assertGatingUnsetIn(final String resourceName) {
        final YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        final Properties properties = factory.getObject();
        assertThat(properties).as("%s should be readable from the classpath", resourceName).isNotNull();

        assertThat(properties.stringPropertyNames())
                .as("%s must not set Liquibase context or label gating — see ADR-043", resourceName)
                .noneMatch(name -> name.startsWith("spring.liquibase.contexts")
                        || name.startsWith("spring.liquibase.label-filter")
                        || name.startsWith("spring.liquibase.labelFilter"));
    }

    /** Returns one {@code file:line} description per occurrence, so a failure names the offender. */
    private List<String> attributeOccurrences(final Pattern pattern) {
        final List<String> occurrences = new ArrayList<>();
        for (final Path file : changelogFiles()) {
            final List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                final Matcher matcher = pattern.matcher(lines.get(index));
                if (matcher.find()) {
                    occurrences.add(file + ":" + (index + 1) + " -> " + lines.get(index).trim());
                }
            }
        }
        return occurrences;
    }

    private List<Path> changelogFiles() {
        try (Stream<Path> tree = Files.walk(CHANGELOG_DIRECTORY)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot walk " + CHANGELOG_DIRECTORY.toAbsolutePath(), exception);
        }
    }

    private List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read " + file, exception);
        }
    }
}
