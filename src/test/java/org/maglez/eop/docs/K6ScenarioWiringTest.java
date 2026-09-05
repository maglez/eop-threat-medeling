package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every k6 scenario in {@code test/k6/} to an actual invocation in
 * {@code .github/workflows/ci.yml}, in both directions.
 *
 * <p>Nothing in the Maven build previously asserted that a k6 scenario is wired into CI. A new
 * {@code test/k6/whatever.js} could be added, reviewed and merged with no {@code run_canary} call:
 * every gate stays green, the file looks like coverage, and the scenario never runs. That is the
 * defect class ADR-055 already names -- <em>a step that only ever runs in CI is only ever proven in
 * CI</em> -- and the adjacent {@code K6ThresholdCouplingTest} does not reach it, because it holds
 * the threshold <em>count</em> in step with {@code options-ci.js} while saying nothing about which
 * scenarios execute.
 *
 * <p><strong>Why this matches an invocation rather than a bare filename.</strong> EOP-204 specified
 * asserting that each filename "appears in" the workflow. Taken literally that check would pass
 * vacuously today: the workflow discusses both {@code health-check.js} and {@code card-catalogue.js}
 * by name in a prose comment explaining why the two scenarios are kept apart, so a scenario that was
 * documented and never invoked would satisfy it. This is the same trap
 * {@code K6ThresholdCouplingTest} avoids by requiring the object-literal form of a threshold rather
 * than the bare word, on the reasoning that a bare match would count the rationale as
 * configuration. So the pattern here is anchored to a {@code run_canary} call at the start of a
 * line, which a comment cannot satisfy.
 *
 * <p><strong>What this test does not do.</strong> It proves each scenario is invoked and each
 * invocation names a scenario that exists. It does not prove the invoking step belongs to a job that
 * runs, that the job is required, or that the scenario asserts anything worth asserting -- a wired
 * scenario whose {@code check()} calls are all trivially true would pass here. It also assumes the
 * invocation names its scenario as a bare literal, which is what the workflow does today; the
 * {@code INVOCATION} Javadoc records the forms that assumption excludes and why each excluded form
 * fails safely. Those stay reviewer-enforced, the same bound {@code K6ThresholdCouplingTest}
 * carries: a declared value is not a sensible one. See EOP-204 and ADR-055.
 */
@DisplayName("Every k6 scenario is invoked by the CI workflow")
class K6ScenarioWiringTest {

    /** The workflow that invokes the scenarios. */
    private static final Path WORKFLOW = Path.of(".github", "workflows", "ci.yml");

    /** The directory holding the scenarios. Its {@code config/} subdirectory is not scenarios. */
    private static final Path SCENARIO_DIR = Path.of("test", "k6");

    /**
     * Matches the scenario name in a {@code run_canary} invocation. Anchored to the start of a line
     * so a mention inside a comment cannot be read as a call, and tolerant of the quoted form.
     *
     * <p>The trailing lookahead is load-bearing and was added because this test's own synthetic case
     * caught its absence. Without it the greedy character class backtracks: given
     * {@code run_canary health-check.js.bak} the group happily settles on the {@code health-check.js}
     * prefix, so an invocation of some adjacent file would report the real scenario as wired.
     *
     * <p><strong>Two forms it deliberately does not match</strong>, both raised in review (EOP-204):
     * a path-prefixed argument such as {@code run_canary test/k6/health-check.js}, because {@code /}
     * is outside the filename class, and a variable such as {@code run_canary "$SCRIPT"}. The
     * workflow passes bare literals and the {@code run_canary} function prepends the mount point
     * itself, so neither form is correct there. Both also fail in the <em>safe</em> direction: an
     * unmatched invocation leaves a real scenario looking unwired, which reddens the build, rather
     * than letting an unwired one look invoked. Widening the class to admit them would trade that
     * asymmetry away for nothing.
     *
     * <p><strong>One form it matches that looks like it should not.</strong>
     * {@code run_canary health-check.js#comment} yields {@code health-check.js}, because {@code #}
     * terminates the class rather than being consumed by it. This is harmless and must not be
     * "fixed": no file of that name can exist, since {@code #} is not in the class the directory
     * listing is filtered against, so {@code shouldNotInvokeMissingScenarios} would fail on the
     * extracted name anyway.
     */
    private static final Pattern INVOCATION =
            Pattern.compile("^\\s*run_canary\\s+\"?([A-Za-z0-9._-]+\\.js)\"?(?![A-Za-z0-9._-])", Pattern.MULTILINE);

    /** Matches the hardcoded scenario count in the failure message that reports how many broke. */
    private static final Pattern FAILURE_DENOMINATOR =
            Pattern.compile("of (\\d+) k6 canary scenarios failed");

    /**
     * Anti-vacuity floor, in the style of {@code K6ThresholdCouplingTest.MINIMUM_THRESHOLDS}: if the
     * scenario directory moved or the glob stopped matching, the scenario list would fall to empty
     * and a rule quantified over an empty list passes while measuring nothing.
     */
    private static final int MINIMUM_SCENARIOS = 2;

    @Test
    @DisplayName("every scenario file is invoked by the workflow")
    void shouldInvokeEveryScenario() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        List<String> scenarios = scenarioFilenames();

        for (String scenario : scenarios) {
            assertThat(invokesScenario(workflow, scenario))
                    .as(
                            "%s exists but %s never invokes it with run_canary. An unwired scenario is dead "
                                    + "weight that reads as coverage: it is reviewed, merged and never executed, so any "
                                    + "regression it would have caught still ships. Add a run_canary call, or delete the "
                                    + "scenario. EOP-204, ADR-055.",
                            SCENARIO_DIR.resolve(scenario), WORKFLOW)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every invocation names a scenario that exists")
    void shouldNotInvokeMissingScenarios() throws IOException {
        List<String> scenarios = scenarioFilenames();

        for (String invoked : invokedScenarios(Files.readString(WORKFLOW))) {
            assertThat(scenarios)
                    .as(
                            "%s invokes run_canary on '%s', but no such file exists in %s. The CI step would "
                                    + "fail on a missing script -- loudly rather than silently -- but the more likely "
                                    + "cause is a rename that updated the workflow and not the file, or the reverse, "
                                    + "which leaves the real scenario unwired. EOP-204.",
                            WORKFLOW, invoked, SCENARIO_DIR)
                    .contains(invoked);
        }
    }

    @Test
    @DisplayName("there are scenarios to check")
    void shouldFindScenariosToCheck() throws IOException {
        List<String> scenarios = scenarioFilenames();

        assertThat(scenarios)
                .as(
                        "found %d scenario(s) in %s, fewer than the floor of %d. Either the scenarios were "
                                + "removed -- in which case the canary tests nothing -- or the directory layout "
                                + "changed and this test is now quantifying over an empty list, which is how it "
                                + "would pass while measuring nothing.",
                        scenarios.size(), SCENARIO_DIR, MINIMUM_SCENARIOS)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SCENARIOS);
    }

    @Test
    @DisplayName("the failure message's scenario count matches the number of invocations")
    void shouldReportTheRightScenarioCount() throws IOException {
        String workflow = Files.readString(WORKFLOW);
        Matcher matcher = FAILURE_DENOMINATOR.matcher(workflow);

        assertThat(matcher.find())
                .as("expected a '<n> of <total> k6 canary scenarios failed' message in %s", WORKFLOW)
                .isTrue();

        int declared = Integer.parseInt(matcher.group(1));
        int invocations = invokedScenarios(workflow).size();

        assertThat(declared)
                .as(
                        "%s reports failures out of %d scenarios but makes %d run_canary calls. The "
                                + "denominator is hardcoded, so adding a scenario without updating it misreports the "
                                + "scale of a red run to whoever is reading the log. Same coupling as "
                                + "EXPECTED_THRESHOLD_COUNT in K6ThresholdCouplingTest. EOP-204.",
                        WORKFLOW, declared, invocations)
                .isEqualTo(invocations);
    }

    @Test
    @DisplayName("the matcher fires on synthetic text and ignores prose")
    void shouldDetectDriftInSyntheticText() {
        String prose =
                """
                          # Two scenarios, two invocations, two summary exports (EOP-156).
                          # health-check.js loads a static string that touches no database, so
                          # on its own the canary could not catch an N+1. card-catalogue.js adds
                          # a request that crosses Postgres and Page<T> serialisation.
                """;
        assertThat(invokesScenario(prose, "health-check.js"))
                .as("a filename discussed in a comment is not an invocation and must not count as one")
                .isFalse();
        assertThat(invokedScenarios(prose))
                .as("prose alone yields no invocations")
                .isEmpty();

        String wired =
                """
                          failures=0
                          run_canary health-check.js "" || failures=$((failures + 1))
                          run_canary "card-catalogue.js" "cards-" || failures=$((failures + 1))
                """;
        assertThat(invokesScenario(wired, "health-check.js"))
                .as("a bare run_canary call must be recognised")
                .isTrue();
        assertThat(invokesScenario(wired, "card-catalogue.js"))
                .as("a quoted run_canary call must be recognised too")
                .isTrue();
        assertThat(invokesScenario(wired, "not-wired.js"))
                .as("a scenario absent from the workflow must not be reported as wired")
                .isFalse();
        assertThat(invokedScenarios(wired))
                .as("both invocations must be extracted, in order")
                .containsExactly("health-check.js", "card-catalogue.js");

        assertThat(invokesScenario("run_canary health-check.js.bak \"\"", "health-check.js"))
                .as("a longer filename sharing a prefix must not satisfy the shorter one")
                .isFalse();

        assertThat(invokedScenarios("run_canary test/k6/health-check.js \"\""))
                .as("a path-prefixed argument is not the form the workflow uses, and must fail safely by matching nothing")
                .isEmpty();
        assertThat(invokedScenarios("run_canary \"$SCRIPT\" \"\""))
                .as("a variable argument is not the form the workflow uses, and must fail safely by matching nothing")
                .isEmpty();
        assertThat(invokedScenarios("run_canary health-check.js#comment"))
                .as("a trailing '#' terminates the filename class rather than being consumed by it")
                .containsExactly("health-check.js");
    }

    /**
     * Lists the scenario filenames, which are the {@code .js} files directly in {@code test/k6/}.
     * Uses a non-recursive listing, so {@code config/} is excluded by construction rather than by a
     * path filter that a later reorganisation could slip past.
     *
     * @return the scenario filenames, sorted for a stable failure message
     * @throws IOException if the scenario directory cannot be read
     */
    private static List<String> scenarioFilenames() throws IOException {
        try (Stream<Path> entries = Files.list(SCENARIO_DIR)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".js"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Reports whether a workflow body invokes a named scenario.
     *
     * @param workflow the text of the workflow file
     * @param scenario the scenario filename to look for
     * @return {@code true} when a {@code run_canary} call names exactly that scenario
     */
    private static boolean invokesScenario(String workflow, String scenario) {
        return invokedScenarios(workflow).contains(scenario);
    }

    /**
     * Extracts every scenario named by a {@code run_canary} invocation, in source order.
     *
     * @param workflow the text of the workflow file
     * @return the invoked scenario filenames, with duplicates preserved
     */
    private static List<String> invokedScenarios(String workflow) {
        Matcher matcher = INVOCATION.matcher(workflow);
        List<String> invoked = new ArrayList<>();
        while (matcher.find()) {
            invoked.add(matcher.group(1));
        }
        return invoked;
    }
}
