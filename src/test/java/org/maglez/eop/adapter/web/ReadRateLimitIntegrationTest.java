package org.maglez.eop.adapter.web;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that exceeding the read rate limit produces an RFC 9457 problem
 * detail rather than a bare status or Spring's default error body (EOP-88).
 *
 * <p>Two things about the setup are deliberate. The limit is lowered for this
 * class only, through {@code @SpringBootTest(properties = …)}, so the rest of the
 * suite keeps the very high limit set in
 * {@code src/test/resources/application.properties} and is never refused because
 * of test ordering. And isolation comes from {@link DirtiesContext}: a fresh
 * context per test method means a fresh counter, so one test cannot leave the
 * next one throttled.
 *
 * <p>What isolation must <em>not</em> come from is a different
 * {@code X-Forwarded-For} per test. Sending one would be forging the limiter key,
 * which is the vulnerability EOP-26 fixed rather than a test technique
 * (ADR-021). Every request here therefore arrives from the same address, exactly
 * as a real caller's would.
 */
@SpringBootTest(properties = "eop.web.read-rate-limit.limit=" + ReadRateLimitIntegrationTest.LIMIT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@DisplayName("read rate limiting")
class ReadRateLimitIntegrationTest {

    /**
     * The limit applied to this class's context. Small enough to exhaust in a few
     * requests, and far below the shipped default of 300, which is what makes the
     * limit demonstrably configurable.
     */
    static final int LIMIT = 3;

    private static final String CARDS = "/api/v1/cards";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    private void exhaustTheLimit() throws Exception {
        for (int read = 0; read < LIMIT; read++) {
            mockMvc.perform(get(CARDS)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the read after the limit is a 429 problem detail carrying Retry-After")
    void shouldReturnProblemDetailWhenTheReadLimitIsExceeded() throws Exception {
        exhaustTheLimit();

        mockMvc.perform(get(CARDS))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("GET /leaderboard is covered: it is refused before the handler decides anything")
    void shouldCoverTheLeaderboardRoute() throws Exception {
        exhaustTheLimit();

        // A random session id with no player token would otherwise be a 403 or a 404.
        // Receiving 429 instead proves the limiter runs ahead of the handler on the
        // route EOP-88 names, without needing a completed game to reach it.
        mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID() + "/leaderboard"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("the event stream is excluded, so subscribing does not consume the read allowance")
    void shouldNotCountTheEventStream() throws Exception {
        for (int attempt = 0; attempt < LIMIT + 2; attempt++) {
            mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID() + "/events"))
                    .andExpect(status().is(not(429)));
        }

        mockMvc.perform(get(CARDS)).andExpect(status().isOk());
    }
}
