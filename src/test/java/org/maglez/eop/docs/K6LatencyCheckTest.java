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
 * Fails the build if a {@code check()} predicate in a k6 scenario asserts against a response timing.
 *
 * <p>EOP-157 deleted a predicate named {@code "response time < 100ms"} from
 * {@code test/k6/health-check.js}. It read as a latency gate and gated nothing: a k6 {@code check()}
 * failure never affects the process exit code, and no threshold over {@code checks} or
 * {@code checks_failed} exists in {@code test/k6/config/options.js} or {@code options-ci.js}. A
 * response between 100 ms and the real ceiling failed that check silently while passing every
 * threshold, so the shape was worse than absent -- it looked like enforcement while enforcing
 * nothing, and it moved the {@code checks} ratio that the {@code perf-trend} job records into
 * {@code ci-history.jsonl} for reasons unrelated to correctness.
 *
 * <p>After EOP-157 the prohibition existed only as prose: a comment block in
 * {@code health-check.js}, the body of commit {@code 7965de6}, and a {@code CHANGELOG.md} entry
 * under {@code ### Removed}. Prose does not fail a build, so nothing stopped the shape being
 * reintroduced. This test is the mechanism (EOP-206).
 *
 * <p><strong>Why the read is bound to a {@code check()} block rather than banned outright.</strong>
 * Reading {@code r.timings.duration} is legitimate outside a check -- logging a slow response, or
 * exporting a custom trend, both of which end up somewhere a human reads rather than in a predicate
 * whose verdict is discarded. Banning the word would forbid the honest use along with the dishonest
 * one, so the matcher extracts the argument text of each {@code check()} call and looks only inside
 * it. That extraction is why this test sanitises before it scans: {@code card-catalogue.js} already
 * carries a predicate whose arrow body wraps across two lines and several that nest parentheses
 * inside {@code r.json("content")}, so a line-oriented or non-nesting matcher would mis-slice the
 * body. Comments and string-literal contents are blanked first (see {@code sanitise}), which also
 * stops the two scenarios' own prose about the deleted predicate from being read as the predicate.
 *
 * <p><strong>What this test does not do.</strong> It proves no {@code check()} asserts on the k6
 * {@code timings} object. It does not prove the remaining predicates are meaningful -- that is the
 * bound {@code K6ScenarioWiringTest} already records, namely that a scenario whose checks are all
 * trivially true passes every gate -- and it does not recognise a latency assertion written without
 * {@code timings} at all, such as arithmetic over {@code Date.now()}. Its sanitiser is a scanner and
 * not a JavaScript parser: a regular-expression literal containing a quote character, and an
 * interpolated {@code ${...}} expression inside a template literal, are both blanked or mis-read
 * rather than analysed. None of those forms appears in either scenario today, and each would be
 * visible to a reviewer reading the diff that introduced it. Those stay reviewer-enforced, the same
 * bound the two adjacent k6 gates carry. See EOP-206, EOP-157 and ADR-055.
 */
@DisplayName("No k6 check() predicate asserts against a response timing")
class K6LatencyCheckTest {

    /** The directory holding the scenarios. Its {@code config/} subdirectory is not scenarios. */
    private static final Path SCENARIO_DIR = Path.of("test", "k6");

    /**
     * Matches the opening parenthesis of a call to k6's {@code check}.
     *
     * <p>The lookbehind excludes an identifier that merely ends in the word -- {@code recheck(} --
     * and a member call such as {@code client.check(}, neither of which is the imported k6 function.
     * It is deliberately not anchored to the start of a line, because a {@code check()} call is
     * indented inside the default function; comments are blanked before this pattern is applied, so
     * a mention of {@code check()} in prose cannot be read as a call.
     */
    private static final Pattern CHECK_CALL = Pattern.compile("(?<![A-Za-z0-9_$.])check\\s*\\(");

    /**
     * Matches a reference to k6's response timing surface.
     *
     * <p>The whole {@code timings} object is the discriminator rather than any one of its seven
     * fields ({@code duration}, {@code blocked}, {@code connecting}, {@code tls_handshaking},
     * {@code sending}, {@code waiting}, {@code receiving}), so a predicate asserting on a phase
     * other than the total is caught by the same rule. Matching the bare word rather than requiring
     * a following {@code .} or {@code [} also catches a destructuring read such as
     * {@code const { duration } = r.timings}. That breadth is only safe because string-literal
     * contents are blanked first: predicate <em>names</em> are string literals inside the argument
     * text, so an innocent name mentioning the word would otherwise fail the build.
     */
    private static final Pattern TIMING_READ = Pattern.compile("\\btimings\\b");

    /**
     * Anti-vacuity floor over the scenario list, in the style of
     * {@code K6ScenarioWiringTest.MINIMUM_SCENARIOS}: if the directory moved or the glob stopped
     * matching, the list would fall to empty and a rule quantified over an empty list passes while
     * measuring nothing.
     */
    private static final int MINIMUM_SCENARIOS = 2;

    /**
     * Anti-vacuity floor over the extracted argument text, and the more important of the two. The
     * file count above cannot tell whether the extractor still works; if {@code CHECK_CALL} or the
     * parenthesis scan broke, every scenario would yield zero bodies and the rule would pass on
     * every file while inspecting nothing at all. Each scenario asserts something, so each must
     * yield at least one body.
     */
    private static final int MINIMUM_CHECKS_PER_SCENARIO = 1;

    @Test
    @DisplayName("no check() predicate reads a response timing")
    void shouldNotAssertOnTimingsInsideACheck() throws IOException {
        for (String scenario : scenarioFilenames()) {
            Path path = SCENARIO_DIR.resolve(scenario);
            for (String body : checkArguments(sanitise(Files.readString(path)))) {
                assertThat(readsTimings(body))
                        .as(
                                "a check() call in %s asserts against the k6 timings object. A k6 check() never "
                                        + "affects the exit code and no threshold covers checks or checks_failed, so a "
                                        + "latency predicate there reads as a gate while gating nothing: a slow response "
                                        + "fails it silently and the run still passes. This is the exact shape EOP-157 "
                                        + "deleted. Latency belongs in the thresholds in test/k6/config/options.js and "
                                        + "options-ci.js, which do fail the run. If the intent is to observe rather than "
                                        + "assert, read the timing outside the check(). EOP-206, EOP-157, ADR-055.",
                                path)
                        .isFalse();
            }
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
                                + "would pass while measuring nothing. EOP-206.",
                        scenarios.size(), SCENARIO_DIR, MINIMUM_SCENARIOS)
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SCENARIOS);
    }

    @Test
    @DisplayName("every scenario yields check() argument text to inspect")
    void shouldFindCheckCallsToInspect() throws IOException {
        for (String scenario : scenarioFilenames()) {
            Path path = SCENARIO_DIR.resolve(scenario);
            List<String> bodies = checkArguments(sanitise(Files.readString(path)));

            assertThat(bodies)
                    .as(
                            "extracted %d check() call(s) from %s, fewer than the floor of %d. Either the "
                                    + "scenario stopped asserting anything, or -- far more likely -- CHECK_CALL or the "
                                    + "parenthesis scan stopped matching, in which case this test inspects an empty "
                                    + "list and passes on every file while proving nothing. EOP-206.",
                            bodies.size(), path, MINIMUM_CHECKS_PER_SCENARIO)
                    .hasSizeGreaterThanOrEqualTo(MINIMUM_CHECKS_PER_SCENARIO);
            assertThat(bodies)
                    .as("a check() call in %s yielded blank argument text, so the scan sliced it wrongly", path)
                    .allSatisfy(body -> assertThat(body.strip()).isNotEmpty());
        }
    }

    @Test
    @DisplayName("the matcher fires on the predicate EOP-157 deleted")
    void shouldDetectTheDeletedPredicateInSyntheticText() {
        String reintroduced =
                """
                          check(res, {
                            "status is 200": (r) => r.status === 200,
                            "response time < 100ms": (r) => r.timings.duration < 100,
                          });
                """;
        assertThat(offendingBodyCount(reintroduced))
                .as("the predicate EOP-157 deleted must be detected, or this gate closes nothing")
                .isOne();

        assertThat(offendingBodyCount("check(res, { \"fast\": (r) => r.timings[\"duration\"] < 100 });"))
                .as("bracket access to a timing field is the same assertion written differently")
                .isOne();
        assertThat(offendingBodyCount("check(res, { \"waited\": (r) => r.timings.waiting < 50 });"))
                .as("a phase other than the total duration is still a timing assertion")
                .isOne();
        assertThat(offendingBodyCount("check(res, { \"fast\": (r) => { const { duration } = r.timings; return duration < 100; } });"))
                .as("a destructuring read is still a timing assertion, which is why the bare word is matched")
                .isOne();

        String wrapped =
                """
                          check(res, {
                            "slow but nested": (r) =>
                              r.timings.duration < Number(r.json("budget")),
                          });
                """;
        assertThat(offendingBodyCount(wrapped))
                .as("a predicate whose arrow body wraps across lines and nests parentheses must still be scanned whole")
                .isOne();

        assertThat(offendingBodyCount("check(res, { \"has ) in the name\": (r) => r.timings.duration < 100 });"))
                .as("a parenthesis inside a predicate name must not truncate the extracted body")
                .isOne();
    }

    @Test
    @DisplayName("timing reads outside a check() are not flagged")
    void shouldIgnoreTimingReadsOutsideACheck() {
        String observational =
                """
                          const res = http.get(`${BASE_URL}/health`);

                          if (res.timings.duration > 100) {
                            console.log(`slow: ${res.timings.duration}ms`);
                          }
                          latency.add(res.timings.waiting);

                          check(res, {
                            "status is 200": (r) => r.status === 200,
                          });
                """;
        assertThat(checkArguments(sanitise(observational)))
                .as("the one check() call must still be extracted")
                .hasSize(1);
        assertThat(offendingBodyCount(observational))
                .as(
                        "reading a timing for logging or a custom metric is legitimate and must not fail the "
                                + "build -- banning the word outright would forbid the honest use with the dishonest one")
                .isZero();
    }

    @Test
    @DisplayName("prose about the prohibition is not read as the prohibited code")
    void shouldIgnoreCommentsAndLiterals() {
        String documented =
                """
                          // EOP-157 deleted a check() named "response time < 100ms" whose predicate read
                          // r.timings.duration. It gated nothing, because a k6 check() never affects the
                          // exit code.
                          /*
                           * Latency is owned by the thresholds, not by check(res, { x: (r) => r.timings.duration });
                           */
                          check(res, {
                            "status is 200": (r) => r.status === 200,
                            "no timings assertion here": (r) => r.body === "OK",
                          });
                """;
        assertThat(offendingBodyCount(documented))
                .as(
                        "both scenarios document the deleted predicate in prose, so a matcher that read comments "
                                + "would fail the build on the very files that record the rule")
                .isZero();
        assertThat(checkArguments(sanitise(documented)))
                .as("a check() call written inside a comment is not a call and must not be extracted")
                .hasSize(1);
        assertThat(offendingBodyCount("check(res, { \"timings are irrelevant here\": (r) => r.status === 200 });"))
                .as("a predicate name mentioning the word is a string literal, not an assertion on it")
                .isZero();
    }

    /**
     * Lists the scenario filenames, which are the {@code .js} files directly in {@code test/k6/}.
     * Uses a non-recursive listing, so {@code config/} is excluded by construction rather than by a
     * path filter that a later reorganisation could slip past. Same idiom as
     * {@code K6ScenarioWiringTest.scenarioFilenames}.
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
     * Counts how many {@code check()} calls in a raw scenario body assert against a timing. Wraps
     * the three steps a caller would otherwise repeat, so a synthetic case reads as one assertion.
     *
     * @param source the raw scenario text, comments and string literals intact
     * @return the number of extracted {@code check()} argument bodies that read a timing
     */
    private static long offendingBodyCount(String source) {
        return checkArguments(sanitise(source)).stream().filter(K6LatencyCheckTest::readsTimings).count();
    }

    /**
     * Reports whether extracted {@code check()} argument text reads the k6 timing surface.
     *
     * @param body the sanitised argument text of a {@code check()} call
     * @return {@code true} when the text references {@code timings}
     */
    private static boolean readsTimings(String body) {
        return TIMING_READ.matcher(body).find();
    }

    /**
     * Extracts the argument text of every {@code check()} call, in source order.
     *
     * <p>Scans parentheses with a depth counter rather than matching to the first {@code )}, because
     * predicates nest calls -- {@code r.json("content")} -- and wrap across lines. The input must
     * already be sanitised, which is what lets the scan ignore quoting: a {@code )} inside a string
     * literal has been blanked and so cannot close the call early. An unterminated call yields the
     * remainder of the file, which fails safe by scanning more text rather than less.
     *
     * @param sanitised scenario text whose comments and string-literal contents have been blanked
     * @return the argument text of each {@code check()} call, excluding the enclosing parentheses
     */
    private static List<String> checkArguments(String sanitised) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = CHECK_CALL.matcher(sanitised);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int depth = 0;
            int close = -1;
            for (int i = open; i < sanitised.length(); i++) {
                char c = sanitised.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            bodies.add(sanitised.substring(open + 1, close == -1 ? sanitised.length() : close));
        }
        return bodies;
    }

    /**
     * Blanks comment bodies and string-literal contents, preserving length and line structure so a
     * failure message and any future line reporting stay truthful.
     *
     * <p>Comments are blanked so the two scenarios' own prose about the deleted predicate is not
     * read as the predicate. String-literal <em>contents</em> are blanked -- the delimiters are
     * kept -- for two reasons: it lets {@code TIMING_READ} match the bare word without tripping over
     * a predicate name that mentions it, and it lets {@code checkArguments} count parentheses
     * without tracking quoting.
     *
     * <p>This is a scanner, not a parser. It does not recognise a regular-expression literal, so a
     * quote character inside one would be read as opening a string, and it blanks the interpolated
     * expression inside a template literal along with the surrounding text. Both bounds are recorded
     * on the class, and neither form appears in either scenario.
     *
     * @param source the raw scenario text
     * @return text of the same length with comment bodies and string contents replaced by spaces
     */
    private static String sanitise(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && peek(source, i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && peek(source, i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < source.length() && !(source.charAt(i) == '*' && peek(source, i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' || c == '\'' || c == '`') {
                out.append(c);
                i++;
                while (i < source.length() && source.charAt(i) != c) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Reads the character at an index without risking an out-of-bounds throw at end of input.
     *
     * @param source the text being scanned
     * @param index the index to read
     * @return the character at that index, or {@code '\0'} when the index is past the end
     */
    private static char peek(String source, int index) {
        return index < source.length() ? source.charAt(index) : '\0';
    }
}
