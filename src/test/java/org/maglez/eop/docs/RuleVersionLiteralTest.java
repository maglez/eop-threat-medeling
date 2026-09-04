package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when a file under {@code .opencode/rules/} states a version literal without
 * citing an ADR on the same line.
 *
 * <p>A rule file is not a decision record. It carries no {@code **Status:**} line, no
 * {@code **Date:**} line and no supersession mechanism, so a version literal written into one can
 * never be retired the way an ADR's can - there is nothing to date it against and nothing that can
 * declare it historical. Worse, every rule file is injected into every agent session, so a stale
 * pin is not merely wrong in a document somebody might read: it is wrong in the prompt of every
 * agent, on every turn.</p>
 *
 * <p>EOP-35 removed a {@code liquibase-core} pin from {@code database.md} for exactly that reason
 * and established no invariant, so the defect recurred in {@code build-quality.md}. This test is
 * the invariant (EOP-125). The escape hatch is deliberately cheap and deliberately visible: cite
 * an ADR on the same line. An ADR is dated and supersedable, so a literal traceable to one has
 * somewhere to be retired from.</p>
 *
 * <p><strong>The recognised shapes are a closed enumeration, and the closure is measured rather
 * than assumed.</strong> Following {@code ADR-006}'s principle for this class of guard, the shapes
 * are enumerated here and must be revisited when a new spelling appears:</p>
 *
 * <ul>
 *   <li>exactly three dot-separated numeric components, bounded so that no word character, dot or
 *       hyphen may sit on either side - {@code 3.11.2}, {@code 4.1.0}</li>
 *   <li>a docker image tag written as {@code image: name:tag} where the tag is numeric -
 *       {@code image: influxdb:1.8}</li>
 * </ul>
 *
 * <p>What is deliberately <em>not</em> recognised, and why, because a guard whose blind spots are
 * unstated is worse than one whose blind spots are named:</p>
 *
 * <ul>
 *   <li><strong>Two-component numbers outside an {@code image:} tag.</strong> A broad
 *       {@code \d+\.\d+} scan of these fifteen files returned the coverage percentages
 *       {@code 95.1}, the k6 threshold rates {@code 0.001} and {@code 0.1}, and the error-rate SLO
 *       {@code 0.1} - three non-versions for every genuine two-component pin. Shape alone cannot
 *       separate them, so a two-component pin outside an image tag is not caught. That is a real
 *       gap, not a claim of completeness.</li>
 *   <li><strong>Four or more components.</strong> {@code 127.0.0.1} is an IP address, and the
 *       trailing {@code .1} is what the lookahead exists to reject.</li>
 *   <li><strong>{@code v}-prefixed tags.</strong> {@code v1.2.0} is rejected by the lookbehind. A
 *       git tag is derived from {@code pom.xml} and is discussed as a naming convention rather than
 *       asserted as a pin.</li>
 *   <li><strong>Image digests.</strong> {@code sha256:...} carries no dotted numeric component at
 *       all, so the pinned-container claims live outside this guard's reach and stay with
 *       {@code tools/supply-chain/}.</li>
 * </ul>
 *
 * <p>An npm-style range such as {@code ^5.7.1} <em>is</em> caught, because {@code ^} is neither a
 * word character, a dot nor a hyphen. None appears in these files today; catching one if it arrives
 * is intended rather than incidental.</p>
 *
 * <p>Scope stops at {@code .opencode/rules/}. It is not extended to {@code docs/} on purpose: an
 * ADR is <em>supposed</em> to carry version literals, which is the whole point of its being dated
 * and supersedable.</p>
 *
 * @see AdrIndexConsistencyTest
 * @see AgentPermissionDeclarationTest
 */
@DisplayName("The rule files must not carry an uncited version literal")
class RuleVersionLiteralTest {

    /** The directory whose files are injected into every agent session. */
    private static final Path RULE_DIRECTORY = Path.of(".opencode", "rules");

    /**
     * Exactly three dot-separated numeric components, with nothing word-like, dotted or hyphenated
     * on either side. The boundaries carry the precision: without the lookahead this would match
     * the first three octets of an IP address, and without the lookbehind it would match the
     * numeric tail of a {@code v}-prefixed git tag.
     */
    private static final Pattern SEMVER = Pattern.compile("(?<![\\w.\\-])\\d+\\.\\d+\\.\\d+(?![\\w.])");

    /**
     * A docker image reference with a numeric tag. This is the second recognised shape and exists
     * because the one genuine two-component pin in these files was an image tag; the surrounding
     * {@code image:} keyword is what distinguishes it from a percentage or a rate.
     */
    private static final Pattern IMAGE_TAG = Pattern.compile("image:\\s*[A-Za-z0-9._/\\-]+:\\d+(?:\\.\\d+)*\\b");

    /** An ADR citation anywhere on the same line is the pass condition. */
    private static final Pattern ADR_CITATION = Pattern.compile("ADR-\\d{3}");

    /**
     * A floor on the number of rule files read, so this test cannot pass by walking an empty or
     * mis-resolved directory. Fifteen files are present; the floor is the count rather than a
     * token value because the directory is loaded by a single glob and its contents are known.
     */
    private static final int MINIMUM_RULE_FILES = 15;

    /**
     * A floor on the number of version literals the detector must still find somewhere in the
     * tree. This is the anti-vacuity assertion: if a future edit removed every literal, or if the
     * pattern were narrowed until it matched nothing, the two invariants below would keep passing
     * while checking nothing at all. Modelled on
     * {@code DeckArithmeticClaimsTest.shouldNotClaimTheDealIsUnevenAtEveryTableSize}.
     */
    private static final int MINIMUM_LITERALS_FOUND = 1;

    /** The shortest acceptable justification for an allow-list entry, in characters. */
    private static final int MINIMUM_JUSTIFICATION_LENGTH = 80;

    /**
     * The declared exceptions. Each is justified in place and each is <em>self-retiring</em>: a
     * separate invariant fails the build when an entry's text no longer appears in its file, so
     * closing an exception means deleting the entry rather than leaving it to decay into a comment
     * about something that is no longer there. The same discipline
     * {@code SeparationInvariantTest} applies to its allowed model-tier overlap.
     *
     * <p>Both entries are in {@code versioning.md}, and neither is a pin this repository carries.
     * Adding a third means arguing that a literal genuinely cannot cite an ADR - which for a real
     * dependency version it always can.</p>
     */
    private static final List<AllowedLiteral> ALLOWED_LITERALS = List.of(
        new AllowedLiteral(
            "versioning.md",
            "Semantic Versioning 2.0.0",
            "2.0.0 is the name of the specification this project follows, not a version of anything "
                + "this repository pins. SemVer 2.0.0 is that specification's current and only "
                + "release, so no dependency bump, Boot upgrade or plugin change can falsify it."),
        new AllowedLiteral(
            "versioning.md",
            "`1.2.0-SNAPSHOT` → tag `v1.2.0`",
            "1.2.0 is a deliberately fictional worked example showing how a development version maps "
                + "onto a release tag. The repository's actual version is 1.0.0-SNAPSHOT, so this "
                + "literal is not a claim about the tree and cannot go stale with it."));

    /**
     * Every three-component version literal in a rule file must cite an ADR on the same line, or
     * be a declared exception.
     *
     * @throws IOException if a rule file cannot be read
     */
    @Test
    @DisplayName("no three-component version literal appears without an ADR citation")
    void shouldNotCarryAnUncitedVersionLiteral() throws IOException {
        assertThat(uncited(SEMVER))
            .as("A rule file states a version literal with no ADR citation on the same line. A rule "
                + "file has no status line, no date and no supersession mechanism, and is injected "
                + "into every agent session, so the literal can never be retired and is wrong in "
                + "every prompt once it drifts. Two fixes, both cheap: cite the ADR that owns the "
                + "decision on the same line, or delete the value and name the file that holds it - "
                + "a file reference cannot go stale, a quoted value can. If neither is honest, add a "
                + "justified entry to ALLOWED_LITERALS. See EOP-125 and ADR-006.")
            .isEmpty();
    }

    /**
     * Every numeric docker image tag in a rule file must cite an ADR on the same line, or be a
     * declared exception.
     *
     * @throws IOException if a rule file cannot be read
     */
    @Test
    @DisplayName("no numeric image tag appears without an ADR citation")
    void shouldNotCarryAnUncitedImageTag() throws IOException {
        assertThat(uncited(IMAGE_TAG))
            .as("A rule file pins a container image by tag with no ADR citation on the same line. "
                + "This is the same defect as an uncited version literal wearing different "
                + "punctuation: the tag moves in compose.yml or docker-compose.yml and the rule file "
                + "has no mechanism to notice. Name the file that holds the tag rather than quoting "
                + "the tag. See EOP-125.")
            .isEmpty();
    }

    /**
     * The detector must still be matching something. Without this, narrowing the pattern or
     * removing every literal would leave the two invariants above green and vacuous.
     *
     * @throws IOException if a rule file cannot be read
     */
    @Test
    @DisplayName("the detector still finds version literals to check")
    void shouldStillFindVersionLiteralsToCheck() throws IOException {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> file : ruleFiles().entrySet()) {
            Matcher matcher = SEMVER.matcher(file.getValue());
            while (matcher.find()) {
                found.add(file.getKey() + ": " + matcher.group());
            }
        }
        assertThat(found)
            .as("SEMVER matched nothing anywhere under %s, so the two invariants above are passing "
                + "vacuously. Either every version literal has genuinely been removed from the rule "
                + "files - in which case delete this guard rather than leave it passing over an "
                + "empty set, per ADR-006's branch-coverage lesson - or the pattern has been "
                + "narrowed until it no longer matches. Do not relax this floor to make a red build "
                + "green.", RULE_DIRECTORY)
            .hasSizeGreaterThanOrEqualTo(MINIMUM_LITERALS_FOUND);
    }

    /**
     * Every declared exception must still describe text that is really in its file, so an
     * exception cannot outlive the thing it excuses.
     *
     * @throws IOException if a rule file cannot be read
     */
    @Test
    @DisplayName("no declared exception outlives the text it excuses")
    void shouldNotAllowAnExceptionThatNoLongerApplies() throws IOException {
        Map<String, String> files = ruleFiles();
        List<String> stale = new ArrayList<>();
        for (AllowedLiteral allowed : ALLOWED_LITERALS) {
            String body = files.get(allowed.file());
            if (body == null || !body.contains(allowed.excerpt())) {
                stale.add(allowed.file() + " no longer contains: " + allowed.excerpt());
            }
        }
        assertThat(stale)
            .as("A declared exception in ALLOWED_LITERALS names text that is no longer in the file. "
                + "That is good news badly recorded: the exception has been closed, so delete the "
                + "entry. Leaving it would let the allow-list grow into a list of excuses for things "
                + "that are not there, which is how an allow-list stops being reviewable.")
            .isEmpty();
    }

    /**
     * Every declared exception must carry an argument, not a label.
     */
    @Test
    @DisplayName("every declared exception carries a written justification")
    void shouldJustifyEveryException() {
        for (AllowedLiteral allowed : ALLOWED_LITERALS) {
            assertThat(allowed.justification())
                .as("The exception for '%s' in %s must explain why the literal cannot cite an ADR "
                    + "and cannot go stale. A short label is not an argument, and this allow-list is "
                    + "the one place a reviewer can be asked to disagree.", allowed.excerpt(), allowed.file())
                .hasSizeGreaterThanOrEqualTo(MINIMUM_JUSTIFICATION_LENGTH);
        }
    }

    /**
     * The walk must find the rule files, so a mis-resolved path cannot make this whole class pass
     * over nothing.
     *
     * @throws IOException if the rule directory cannot be read
     */
    @Test
    @DisplayName("the walk reads every rule file")
    void shouldReadEveryRuleFile() throws IOException {
        assertThat(ruleFiles())
            .as("Fewer than %d files were read from %s. Either the directory moved, or the working "
                + "directory is not the repository root, or a rule file was deleted. Nothing in this "
                + "class means anything if the walk found nothing, so this floor fails loudly rather "
                + "than letting the invariants pass over an empty map.", MINIMUM_RULE_FILES, RULE_DIRECTORY)
            .hasSizeGreaterThanOrEqualTo(MINIMUM_RULE_FILES);
    }

    /**
     * Proves both matchers fire, and that the four deliberately excluded shapes do not, against
     * synthetic text rather than against the tree. A guard that has only ever been seen to pass is
     * not evidence.
     */
    @Test
    @DisplayName("both matchers fire on synthetic text, and the excluded shapes do not")
    void shouldDetectAnUncitedLiteralInSyntheticText() {
        assertThat(SEMVER.matcher("pinned at 3.11.2 in pom.xml").find())
            .as("SEMVER must match a bare three-component literal")
            .isTrue();
        assertThat(SEMVER.matcher("the ADR-006 gate pins 3.11.2").find())
            .as("SEMVER matches the literal regardless of the citation; the citation is checked "
                + "separately, on the line")
            .isTrue();
        assertThat(ADR_CITATION.matcher("the ADR-006 gate pins 3.11.2").find())
            .as("ADR_CITATION must recognise a same-line citation")
            .isTrue();
        assertThat(ADR_CITATION.matcher("pinned at 3.11.2 in pom.xml").find())
            .as("ADR_CITATION must not invent a citation that is absent")
            .isFalse();

        assertThat(IMAGE_TAG.matcher("its version is pinned: `image: influxdb:1.8`").find())
            .as("IMAGE_TAG must match a numeric docker tag, which is the shape SEMVER cannot see")
            .isTrue();
        assertThat(IMAGE_TAG.matcher("run `image: influxdb:latest` locally").find())
            .as("IMAGE_TAG must ignore a non-numeric tag, which cannot drift into a wrong number")
            .isFalse();

        assertThat(SEMVER.matcher("InfluxDB listens on 127.0.0.1:8086").find())
            .as("an IP address must not read as a version - this is what the lookahead is for")
            .isFalse();
        assertThat(SEMVER.matcher("coverage read 95.1% before and after").find())
            .as("a percentage has too few components to be recognised")
            .isFalse();
        assertThat(SEMVER.matcher("error rate < 0.1%").find())
            .as("a rate has too few components to be recognised")
            .isFalse();
        assertThat(SEMVER.matcher("mirror it as tag v1.2.0").find())
            .as("a v-prefixed git tag must not read as a version - this is what the lookbehind is for")
            .isFalse();
    }

    /**
     * Returns every line under {@link #RULE_DIRECTORY} that contains a match for the given pattern
     * without an ADR citation on the same line and without a declared exception covering it.
     *
     * @param pattern the version-literal shape to look for
     * @return the offending locations, each as {@code file:line - text}, empty when the tree is clean
     * @throws IOException if a rule file cannot be read
     */
    private static List<String> uncited(Pattern pattern) throws IOException {
        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, String> file : ruleFiles().entrySet()) {
            String[] lines = file.getValue().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!pattern.matcher(line).find()) {
                    continue;
                }
                if (ADR_CITATION.matcher(line).find() || isAllowed(file.getKey(), line)) {
                    continue;
                }
                offences.add(file.getKey() + ":" + (i + 1) + " - " + line.trim());
            }
        }
        return offences;
    }

    /**
     * Reports whether a declared exception covers the given line.
     *
     * @param file the rule file's name, without its directory
     * @param line the line under inspection
     * @return {@code true} when an entry in {@link #ALLOWED_LITERALS} names this file and its
     *     excerpt appears on this line
     */
    private static boolean isAllowed(String file, String line) {
        return ALLOWED_LITERALS.stream()
            .anyMatch(allowed -> allowed.file().equals(file) && line.contains(allowed.excerpt()));
    }

    /**
     * Reads every Markdown file in the rule directory.
     *
     * @return a map of file name, without its directory, to the whole file's text
     * @throws IOException if the directory or one of its files cannot be read
     */
    private static Map<String, String> ruleFiles() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(RULE_DIRECTORY)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted()
                .forEach(path -> {
                    try {
                        files.put(path.getFileName().toString(), Files.readString(path));
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read rule file " + path, e);
                    }
                });
        }
        return files;
    }

    /**
     * A declared, justified, self-retiring exception to the citation requirement.
     *
     * @param file the rule file's name, without its directory
     * @param excerpt text that must still appear in that file, and that identifies the line the
     *     exception covers
     * @param justification why this literal cannot cite an ADR and cannot go stale
     */
    private record AllowedLiteral(String file, String excerpt, String justification) {
    }
}
