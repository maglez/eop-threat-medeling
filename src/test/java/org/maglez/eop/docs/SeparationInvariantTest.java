package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the Separation Invariant of ADR-022 rule 5 against the actual model pins, so that it can no longer be
 * asserted only in prose.
 *
 * <p>The invariant is that no Definition-of-Done gate shares a model identifier with an agent that authors an
 * artefact class that gate reviews. Identical weights on both sides of a review is not review at all in the sense
 * Blueprint §3.1 means: it is neither <em>family</em>-independent nor even <em>model</em>-independent. Until
 * 2026-08-24 this held with one documented exception — {@code @code-reviewer} and both tester gates all resolved to
 * {@code MODEL_B} — which ADR-059 closed by moving {@code @code-reviewer} alone onto a sixth tier, {@code MODEL_G}.
 *
 * <p><strong>This test does not claim the invariant is unconditional.</strong> It holds with zero exceptions for
 * production code, infrastructure and test code. One overlap survives and is declared in {@link #ALLOWED_OVERLAPS}:
 * {@code @security-auditor} and {@code @architecture-guardian} share {@code MODEL_F}, and the latter authors ADRs and
 * C4 models, so architecture documentation is still reviewed by a tier-mate at one model identifier.
 *
 * <p>Two facts are read from the working tree rather than hardcoded: the agent-to-tier mapping in
 * {@code .opencode/opencode.json} and the tier-to-model-identifier mapping in the active block of
 * {@code .env.example}. Nothing here requires OpenCode to be installed or any environment variable to be set, and a
 * tier variable that cannot be resolved fails loudly rather than skipping an assertion — a guard that silently
 * passes when it cannot evaluate itself is worse than no guard.
 *
 * <p>The gate-to-artefact-class and author-to-artefact-class mappings are declared explicitly below. They are
 * semantic facts about what each agent does, which no configuration file encodes, so they are maintained by hand and
 * tied to ADR-022 rule 5 by comment.
 *
 * <p>Surefire runs with the working directory set to the project base directory, so the relative paths resolve.
 *
 * @see <a href="../../../../../../../docs/adr/ADR-059-code-review-gate-on-its-own-model-tier.md">ADR-059</a>
 */
@DisplayName("The Separation Invariant over the agent model pins")
class SeparationInvariantTest {

    /** The single authoritative agent-to-tier table, read as JSON from the working tree. */
    private static final Path AGENT_CONFIGURATION = Path.of(".opencode/opencode.json");

    /**
     * The tracked, reviewable source of tier-to-model-identifier mappings. The live {@code .env} is gitignored and
     * may be absent or locally edited, so the template's active (uncommented) block is what this test resolves
     * against.
     */
    private static final Path ENVIRONMENT_TEMPLATE = Path.of(".env.example");

    /** Matches the {@code {env:MODEL_X}} indirection the agent block uses instead of a literal model identifier. */
    private static final Pattern TIER_PLACEHOLDER = Pattern.compile("^\\{env:(MODEL_[A-Z])}$");

    /** Matches an active (uncommented, column-zero) tier assignment in {@code .env.example}. */
    private static final Pattern ACTIVE_ASSIGNMENT = Pattern.compile("^(MODEL_[A-Z])=(\\S+)\\s*$");

    /**
     * What each Definition-of-Done gate reviews, per §12.8 of the Blueprint and ADR-022 rule 5.
     *
     * <p>The two tester gates verify production code by writing tests against it; they do not review another agent's
     * test code, which is {@code @code-reviewer}'s remit. {@code @architecture-guardian} reviews design and the
     * documentation of it. {@code @security-auditor} is the broadest, auditing everything.
     *
     * <p>{@code @sonarqube-expert} reviews production code only. It reviewed test code as well until 2026-09-02, when
     * the SonarQube ratchet narrowed to the MAIN scope (EOP-000, ADR-060 as amended): the gate now adjudicates three
     * counts over {@code src/main/java} alone, so a test-code finding is measured and recorded but never gated, and
     * there is nothing in that tree for this gate to adjudicate. Before the narrowing 211 of the 243 findings sat
     * under {@code src/test/java} against a ceiling with no headroom, which is what let a routine new test file
     * redden a gate that had nothing to say about the product.
     *
     * <p>{@code @dependency-vulnerability} reviews production code and infrastructure because its subject is the two
     * shipped dependency manifests, {@code pom.xml} and {@code ui/package-lock.json}.
     */
    private static final Map<String, Set<ArtefactClass>> GATE_REVIEWS = Map.of(
            "code-reviewer", EnumSet.of(ArtefactClass.PRODUCTION_CODE, ArtefactClass.INFRASTRUCTURE, ArtefactClass.TEST_CODE),
            "security-auditor", EnumSet.allOf(ArtefactClass.class),
            "architecture-guardian", EnumSet.of(ArtefactClass.PRODUCTION_CODE, ArtefactClass.ARCHITECTURE_DOCUMENTATION),
            "tester-unit-and-quality", EnumSet.of(ArtefactClass.PRODUCTION_CODE),
            "tester-api", EnumSet.of(ArtefactClass.PRODUCTION_CODE),
            "sonarqube-expert", EnumSet.of(ArtefactClass.PRODUCTION_CODE),
            "dependency-vulnerability", EnumSet.of(ArtefactClass.PRODUCTION_CODE, ArtefactClass.INFRASTRUCTURE));

    /**
     * What each authoring agent produces, per ADR-022 rule 5's enumeration of artefact classes.
     *
     * <p>{@code @tech-lead} is included because it authors Java directly rather than always delegating — the
     * primary-agent case Blueprint §3.2 records. {@code @ui-builder} is credited with test code as well as
     * production code, because the front-end Vitest suites under {@code ui/src} are its work too.
     * {@code @product-owner} and the four expert advisers are absent
     * deliberately: they author requirements and advice, which sit outside the review path.
     */
    private static final Map<String, Set<ArtefactClass>> AGENT_AUTHORS = Map.of(
            "tech-lead", EnumSet.of(ArtefactClass.PRODUCTION_CODE),
            "ui-builder", EnumSet.of(ArtefactClass.PRODUCTION_CODE, ArtefactClass.TEST_CODE),
            "db-designer", EnumSet.of(ArtefactClass.INFRASTRUCTURE),
            "devops-engineer", EnumSet.of(ArtefactClass.INFRASTRUCTURE),
            "performance-engineer", EnumSet.of(ArtefactClass.INFRASTRUCTURE),
            "tester-unit-and-quality", EnumSet.of(ArtefactClass.TEST_CODE),
            "tester-api", EnumSet.of(ArtefactClass.TEST_CODE),
            "architecture-guardian", EnumSet.of(ArtefactClass.ARCHITECTURE_DOCUMENTATION));

    /**
     * The overlaps that are knowingly accepted, each carrying the reason it is accepted.
     *
     * <p>This list is declared, justified and self-retiring. An entry with a blank justification fails, and an entry
     * that no longer describes a real overlap fails too — so closing an exception means <em>deleting</em> its entry
     * rather than leaving it passing over nothing, per the lesson ADR-006 records for the branch-coverage limit and
     * the allow-list precedent ADR-052 sets.
     */
    private static final List<DeclaredOverlap> ALLOWED_OVERLAPS = List.of(new DeclaredOverlap(
            "security-auditor",
            "architecture-guardian",
            ArtefactClass.ARCHITECTURE_DOCUMENTATION,
            "ADR-059 moved only @code-reviewer to a sixth tier. @security-auditor and @architecture-guardian remain "
                    + "together on MODEL_F, and @architecture-guardian authors ADRs and C4 models, so architecture "
                    + "documentation is reviewed by a tier-mate at one model identifier — neither model- nor "
                    + "family-independent. Accepted because the two gates' capability requirements are identical and a "
                    + "seventh tier would multiply drift across the tier tables for a documentation artefact that a human "
                    + "also reviews on every pull request. Closing it means pinning @security-auditor to its own tier and "
                    + "deleting this entry."));

    /**
     * A floor on the number of agents read out of the configuration, so this test cannot pass by parsing nothing.
     * Seventeen agents are pinned at the time of writing: three on MODEL_A, five on MODEL_B, three on MODEL_C, one on
     * MODEL_E, four on MODEL_F and one on MODEL_G. Raise it when agents are added; lower it only alongside a
     * deliberate decision to remove one.
     */
    private static final int MINIMUM_AGENTS = 17;

    /**
     * A floor on the number of distinct tier variables the agent block references. Six at the time of writing —
     * MODEL_A, B, C, E, F and G; MODEL_D is {@code small_model} only and no agent uses it. Lower it only alongside a
     * deliberate decision to collapse a tier.
     */
    private static final int MINIMUM_TIERS_IN_USE = 6;

    /**
     * A floor on the number of gate-against-author comparisons actually performed, so neither the invariant rule nor
     * the allow-list rules can pass by comparing nothing. Twenty-seven meaningful comparisons exist at the time of
     * writing. That is down from thirty: on 2026-09-02 the SonarQube ratchet narrowed to production code, so
     * {@code @sonarqube-expert} stopped reviewing test code (EOP-000, ADR-060 as amended), which retired its three
     * comparisons against the three {@code TEST_CODE} authors — {@code @ui-builder} and the two tester gates. It was
     * twenty-one before {@code @sonarqube-expert} and {@code @dependency-vulnerability} became gates at all. The
     * floor sits a little below the true count so removing one authoring agent does not fail this check for the wrong
     * reason. Raise it when a gate or an authoring agent is added.
     */
    private static final int MINIMUM_COMPARISONS = 24;

    /** The shortest justification accepted on a declared overlap, so a placeholder cannot stand in for a reason. */
    private static final int MINIMUM_JUSTIFICATION_LENGTH = 80;

    @Test
    @DisplayName("holds with zero exceptions for production code, infrastructure and test code")
    void shouldKeepEveryGateOffTheModelThatAuthoredWhatItReviews() {
        final List<Overlap> undeclared = new ArrayList<>(detectOverlaps(resolveAgentModels()));
        undeclared.removeIf(SeparationInvariantTest::isDeclared);

        assertThat(undeclared)
                .as("A Definition-of-Done gate shares a model identifier with an agent that authored an artefact class "
                        + "that gate reviews, so that review is neither family- nor model-independent (Blueprint §3.1, "
                        + "ADR-022 rule 5, ADR-059). Either repin the gate to a tier no such author occupies, or — if the "
                        + "overlap is knowingly accepted — add a justified entry to ALLOWED_OVERLAPS. Offending pairs: "
                        + describe(undeclared))
                .isEmpty();
    }

    @Test
    @DisplayName("declares its surviving overlap with a justification rather than silently tolerating it")
    void shouldJustifyEveryDeclaredOverlap() {
        assertThat(ALLOWED_OVERLAPS)
                .as("The allow-list is the only sanctioned way to accept an overlap, so it must not be empty while the "
                        + "architecture-documentation overlap survives — see ADR-059.")
                .isNotEmpty();

        for (final DeclaredOverlap declared : ALLOWED_OVERLAPS) {
            assertThat(declared.justification())
                    .as("The declared overlap %s carries no usable justification. An accepted exception must record why "
                            + "it is accepted and what closing it would take, or it decays into an unexplained hole.", declared.pair())
                    .isNotBlank()
                    .hasSizeGreaterThanOrEqualTo(MINIMUM_JUSTIFICATION_LENGTH);
        }
    }

    @Test
    @DisplayName("forces a closed exception to be deleted rather than left passing over nothing")
    void shouldNotDeclareAnOverlapThatNoLongerExists() {
        final List<Overlap> actual = detectOverlaps(resolveAgentModels());

        for (final DeclaredOverlap declared : ALLOWED_OVERLAPS) {
            assertThat(actual)
                    .as("ALLOWED_OVERLAPS still declares %s, but that is no longer a real overlap — the two agents no "
                            + "longer share a model identifier. Delete the entry instead of leaving it passing over "
                            + "nothing; a guard that can no longer fire must be fixed or deleted, never left green "
                            + "(ADR-006).", declared.pair())
                    .anyMatch(declared::matches);
        }
    }

    @Test
    @DisplayName("resolves every tier variable it reads, failing loudly rather than skipping an assertion")
    void shouldResolveEveryTierVariableTheAgentBlockReferences() {
        final Map<String, String> tierIdentifiers = readActiveTierIdentifiers();
        final Map<String, String> agentTiers = readAgentTiers();

        assertThat(agentTiers.values())
                .as("Every agent pin must use the {env:MODEL_X} indirection so that provider switching stays a matter of "
                        + "editing .env alone (Blueprint §3.4.2). A literal model identifier in the agent block breaks "
                        + "that and this test cannot resolve it.")
                .allMatch(tier -> tier.startsWith("MODEL_"));

        assertThat(tierIdentifiers.keySet())
                .as("A tier variable referenced by an agent has no active assignment in %s, so this test cannot tell which "
                        + "model that agent runs on. Add the assignment to the active provider block — never skip the "
                        + "assertion, because a guard that silently passes when it cannot evaluate itself is worse than no "
                        + "guard.", ENVIRONMENT_TEMPLATE)
                .containsAll(agentTiers.values());
    }

    @Test
    @DisplayName("is actually reading the pins it guards, so none of the rules above can pass by matching nothing")
    void shouldFindTheAgentsAndComparisonsItGuards() {
        final Map<String, String> agentModels = resolveAgentModels();

        assertThat(agentModels)
                .as("No agent pins were parsed out of %s — is the working directory the project root?", AGENT_CONFIGURATION)
                .isNotEmpty()
                .hasSizeGreaterThanOrEqualTo(MINIMUM_AGENTS);

        assertThat(agentModels.keySet())
                .as("GATE_REVIEWS or AGENT_AUTHORS names an agent that no longer appears in %s. detectOverlaps skips a "
                        + "name it cannot resolve, so a renamed or removed agent would drop out of the comparison "
                        + "silently and this guard would keep passing while no longer checking that agent at all. Fix "
                        + "the map in the same change as the rename.", AGENT_CONFIGURATION)
                .containsAll(GATE_REVIEWS.keySet())
                .containsAll(AGENT_AUTHORS.keySet());

        assertThat(Set.copyOf(readAgentTiers().values()))
                .as("Fewer distinct tier variables are in use than expected. If a tier was deliberately collapsed, lower "
                        + "MINIMUM_TIERS_IN_USE in the same change and say why; otherwise the agent block has lost a pin.")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_TIERS_IN_USE);

        assertThat(countComparisons(agentModels))
                .as("Too few gate-against-author comparisons were performed, so the invariant rule risks passing by "
                        + "comparing nothing — the failure mode ADR-006 records for the branch-coverage limit. Check that "
                        + "GATE_REVIEWS and AGENT_AUTHORS still name agents that exist in the configuration.")
                .isGreaterThanOrEqualTo(MINIMUM_COMPARISONS);
    }

    @Test
    @DisplayName("detects a collision when fed one, proving the rule fires independently of the real configuration")
    void shouldDetectAnOverlapInASyntheticAllocation() {
        final Map<String, String> colliding = Map.of(
                "code-reviewer", "provider/some-model",
                "tester-api", "provider/some-model",
                "tester-unit-and-quality", "provider/other-model");

        final List<Overlap> overlaps = detectOverlaps(colliding);

        assertThat(overlaps)
                .as("A gate and an author pinned to the same identifier must be reported. If this fails, the detector is "
                        + "broken and every green run of the rules above is meaningless.")
                .hasSize(1)
                .first()
                .satisfies(overlap -> {
                    assertThat(overlap.gate()).isEqualTo("code-reviewer");
                    assertThat(overlap.author()).isEqualTo("tester-api");
                    assertThat(overlap.artefactClass()).isEqualTo(ArtefactClass.TEST_CODE);
                });

        assertThat(detectOverlaps(Map.of("code-reviewer", "provider/one", "tester-api", "provider/two")))
                .as("Two different identifiers are not an overlap; reporting one would make the rule useless.")
                .isEmpty();
    }

    /**
     * Finds every case of a gate sharing a model identifier with a different agent that authors something the gate
     * reviews.
     *
     * <p>Self-pairs are excluded on purpose. A tester gate reviewing the tests it wrote itself is inherent to the
     * role and cannot be fixed by any allocation of models; the invariant governs <em>cross-agent</em> review, which
     * is what a model pin can actually change.
     *
     * @param agentModels agent name to resolved model identifier
     * @return every overlap found, in a stable order
     */
    private static List<Overlap> detectOverlaps(final Map<String, String> agentModels) {
        final List<Overlap> overlaps = new ArrayList<>();
        for (final Map.Entry<String, Set<ArtefactClass>> gate : new TreeMap<>(GATE_REVIEWS).entrySet()) {
            final String gateModel = agentModels.get(gate.getKey());
            if (gateModel == null) {
                continue;
            }
            for (final Map.Entry<String, Set<ArtefactClass>> author : new TreeMap<>(AGENT_AUTHORS).entrySet()) {
                if (author.getKey().equals(gate.getKey()) || !gateModel.equals(agentModels.get(author.getKey()))) {
                    continue;
                }
                for (final ArtefactClass artefactClass : author.getValue()) {
                    if (gate.getValue().contains(artefactClass)) {
                        overlaps.add(new Overlap(gate.getKey(), author.getKey(), artefactClass, gateModel));
                    }
                }
            }
        }
        return List.copyOf(overlaps);
    }

    /**
     * Counts the gate-against-author pairs where the gate reviews at least one artefact class the author produces —
     * the comparisons the invariant rule actually makes, whatever the pins happen to be.
     *
     * @param agentModels agent name to resolved model identifier
     * @return the number of meaningful comparisons
     */
    private static int countComparisons(final Map<String, String> agentModels) {
        int comparisons = 0;
        for (final Map.Entry<String, Set<ArtefactClass>> gate : GATE_REVIEWS.entrySet()) {
            if (!agentModels.containsKey(gate.getKey())) {
                continue;
            }
            for (final Map.Entry<String, Set<ArtefactClass>> author : AGENT_AUTHORS.entrySet()) {
                final boolean reviewsSomethingTheAuthorWrites = author.getValue().stream().anyMatch(gate.getValue()::contains);
                if (!author.getKey().equals(gate.getKey()) && agentModels.containsKey(author.getKey()) && reviewsSomethingTheAuthorWrites) {
                    comparisons++;
                }
            }
        }
        return comparisons;
    }

    /**
     * Whether an overlap is covered by a declared allow-list entry.
     *
     * @param overlap the overlap found in the configuration
     * @return {@code true} when an entry declares it
     */
    private static boolean isDeclared(final Overlap overlap) {
        return ALLOWED_OVERLAPS.stream().anyMatch(declared -> declared.matches(overlap));
    }

    /**
     * Resolves each agent's tier variable to the model identifier assigned in the active provider block.
     *
     * @return agent name to resolved model identifier
     * @throws IllegalStateException when a referenced tier variable has no active assignment
     */
    private static Map<String, String> resolveAgentModels() {
        final Map<String, String> tierIdentifiers = readActiveTierIdentifiers();
        final Map<String, String> resolved = new LinkedHashMap<>();
        for (final Map.Entry<String, String> pin : readAgentTiers().entrySet()) {
            final String identifier = tierIdentifiers.get(pin.getValue());
            if (identifier == null) {
                throw new IllegalStateException("agent " + pin.getKey() + " is pinned to " + pin.getValue()
                        + ", which has no active assignment in " + ENVIRONMENT_TEMPLATE
                        + " — resolve it rather than skipping the Separation Invariant");
            }
            resolved.put(pin.getKey(), identifier);
        }
        return Map.copyOf(resolved);
    }

    /**
     * Reads the agent-to-tier-variable pins out of the {@code agent} block of the OpenCode configuration.
     *
     * @return agent name to tier variable, e.g. {@code code-reviewer -> MODEL_G}
     */
    private static Map<String, String> readAgentTiers() {
        final JsonNode agents = readConfiguration().path("agent");
        final Map<String, String> pins = new LinkedHashMap<>();
        agents.fields().forEachRemaining(entry -> {
            final String model = entry.getValue().path("model").asText("");
            final Matcher placeholder = TIER_PLACEHOLDER.matcher(model);
            pins.put(entry.getKey(), placeholder.matches() ? placeholder.group(1) : model);
        });
        return Map.copyOf(pins);
    }

    /**
     * Reads the active — uncommented, column-zero — tier assignments from the environment template. The inactive
     * provider block is commented out and is therefore not what a running session would resolve.
     *
     * @return tier variable to model identifier
     */
    private static Map<String, String> readActiveTierIdentifiers() {
        final Map<String, String> assignments = new LinkedHashMap<>();
        for (final String line : readLines(ENVIRONMENT_TEMPLATE)) {
            final Matcher assignment = ACTIVE_ASSIGNMENT.matcher(line);
            if (assignment.matches()) {
                assignments.put(assignment.group(1), assignment.group(2));
            }
        }
        return Map.copyOf(assignments);
    }

    /**
     * Parses the OpenCode configuration as JSON from the working tree.
     *
     * @return the parsed document
     */
    private static JsonNode readConfiguration() {
        try {
            return new ObjectMapper().readTree(AGENT_CONFIGURATION.toFile());
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + AGENT_CONFIGURATION, cause);
        }
    }

    /**
     * Reads a file as lines, converting the checked exception so the tests stay readable.
     *
     * @param file the file to read
     * @return its lines
     */
    private static List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file);
        } catch (final IOException cause) {
            throw new UncheckedIOException("could not read " + file, cause);
        }
    }

    /**
     * Renders overlaps for an assertion message.
     *
     * @param overlaps the overlaps to render
     * @return a readable one-line description of each
     */
    private static String describe(final List<Overlap> overlaps) {
        return overlaps.stream().map(Overlap::describe).toList().toString();
    }

    /** The classes of artefact ADR-022 rule 5 enumerates, plus the documentation class its surviving exception concerns. */
    private enum ArtefactClass {
        /** Java and TypeScript that ships to users. */
        PRODUCTION_CODE,
        /** Liquibase migrations, CI workflows, container and compose definitions. */
        INFRASTRUCTURE,
        /** Everything under {@code src/test} and {@code ui/src/**\/*.test.ts}. */
        TEST_CODE,
        /** ADRs, C4 models and the architecture documents under {@code docs/}. */
        ARCHITECTURE_DOCUMENTATION
    }

    /**
     * A gate reviewing an artefact class produced by a different agent pinned to the same model identifier.
     *
     * @param gate the reviewing agent
     * @param author the authoring agent
     * @param artefactClass the class of artefact they share
     * @param modelIdentifier the identifier both resolve to
     */
    private record Overlap(String gate, String author, ArtefactClass artefactClass, String modelIdentifier) {

        /**
         * Describes the overlap for an assertion message.
         *
         * @return a one-line description
         */
        String describe() {
            return "@%s reviews %s authored by @%s, both on %s".formatted(gate, artefactClass, author, modelIdentifier);
        }
    }

    /**
     * A knowingly accepted overlap and the reason it is accepted.
     *
     * @param gate the reviewing agent
     * @param author the authoring agent
     * @param artefactClass the class of artefact they share
     * @param justification why the overlap is accepted, and what closing it would take
     */
    private record DeclaredOverlap(String gate, String author, ArtefactClass artefactClass, String justification) {

        /**
         * Whether this entry declares the given overlap.
         *
         * @param overlap the overlap found in the configuration
         * @return {@code true} when they describe the same pair and artefact class
         */
        boolean matches(final Overlap overlap) {
            return gate.equals(overlap.gate()) && author.equals(overlap.author()) && artefactClass == overlap.artefactClass();
        }

        /**
         * Names the declared pair for an assertion message.
         *
         * @return a one-line description
         */
        String pair() {
            return "@%s reviewing %s authored by @%s".formatted(gate, artefactClass, author);
        }
    }
}
