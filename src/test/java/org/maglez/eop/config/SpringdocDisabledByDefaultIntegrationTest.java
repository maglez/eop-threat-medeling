package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;

/**
 * Proves the shipped default serves no API schema and no Swagger UI (EOP-38, ADR-049).
 *
 * <p>Before EOP-38 every piece of production hardening lived in {@code application-prod.yml},
 * so it applied only when {@code SPRING_PROFILES_ACTIVE=prod} was set. {@code application.yml}
 * carried no {@code springdoc} block at all, which meant omitting one environment variable
 * produced the permissive state: the default profile served a full 18,739-byte OpenAPI
 * document at {@code /v3/api-docs} and a working Swagger UI. The safe configuration was the
 * one you had to remember to ask for, which inverts the "fail securely, default-denied
 * access" rule this project claims to follow. EOP-38 moved both keys into the base file so
 * the default is closed and the relaxation is what takes an action.
 *
 * <p>These assertions are real gates, and the reason is deliberate and easy to destroy.
 * {@code src/test/resources/application.properties} sets neither springdoc property, so this
 * suite inherits whatever {@code application.yml} actually ships. Pin either property there
 * and every assertion below would start passing because of that file rather than because of
 * what we deploy — exactly the trap EOP-27 documented for {@code spring.h2.console.enabled}
 * in the same file. Verified by mutation: flipping the base back to {@code true} fails
 * {@link #shouldResolveTheShippedDefaultToFalse()} and all three 404 assertions.
 *
 * <p>{@link #shouldStillServeTheApplication()} is a positive control rather than a claim about
 * springdoc. Three assertions that something answers 404 would also pass against a context
 * that failed to start, or a server bound to a port nothing reached, so it would be possible
 * to satisfy this class for entirely the wrong reason and never know. Asserting that a real
 * endpoint answers 200 over the same client and the same port removes that reading. The
 * pattern is borrowed from {@code tools/artifact/assert-no-h2-in-jar.sh}, which asserts the
 * PostgreSQL driver is present precisely so that a truncated jar cannot pass its absence check.
 *
 * <p>What this class does <em>not</em> prove is that the schema is unreachable everywhere. It
 * proves the default is closed. The opt-in is still honoured, deliberately, and
 * {@link SpringdocOptInIntegrationTest} pins that — because "disabled" must not be satisfiable
 * by springdoc merely being broken. {@code application-prod.yml} continues to pin both keys to
 * {@code false} as an independent second guard, which no test here observes.
 *
 * <p>A real server over a socket is used rather than {@code MockMvc} for the same reason
 * {@code H2ConsoleAbsentIntegrationTest} gives: springdoc's endpoints are conditionally
 * registered handler mappings, and a request against a context that never registered them
 * needs to travel the full dispatch path for the 404 to mean what it appears to mean. The
 * client is the JDK's own {@link HttpClient}, matching {@code SessionStreamIntegrationTest},
 * because this project has no WebFlux dependency and therefore no {@code WebClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("springdoc on the shipped configuration")
class SpringdocDisabledByDefaultIntegrationTest {

    private static final String API_DOCS = "/v3/api-docs";

    private static final String SWAGGER_UI = "/swagger-ui/index.html";

    private static final String SWAGGER_UI_LEGACY = "/swagger-ui.html";

    private static final String API_DOCS_ENABLED = "springdoc.api-docs.enabled";

    private static final String SWAGGER_UI_ENABLED = "springdoc.swagger-ui.enabled";

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void hangUp() {
        client.shutdownNow();
    }

    @Test
    @DisplayName("resolves both shipped defaults to false")
    void shouldResolveTheShippedDefaultToFalse() {
        assertThat(environment.getProperty(API_DOCS_ENABLED, Boolean.class, Boolean.TRUE)).isFalse();
        assertThat(environment.getProperty(SWAGGER_UI_ENABLED, Boolean.class, Boolean.TRUE)).isFalse();
    }

    @Test
    @DisplayName("serves no OpenAPI document")
    void shouldNotServeTheApiDocument() throws IOException, InterruptedException {
        HttpResponse<String> response = getBody(API_DOCS);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("\"openapi\"");
    }

    @Test
    @DisplayName("serves no Swagger UI, on either path")
    void shouldNotServeSwaggerUi() throws IOException, InterruptedException {
        assertThat(statusOf(SWAGGER_UI)).isEqualTo(404);
        assertThat(statusOf(SWAGGER_UI_LEGACY)).isEqualTo(404);
    }

    @Test
    @DisplayName("still serves the application, so the 404s above are not vacuous")
    void shouldStillServeTheApplication() throws IOException, InterruptedException {
        assertThat(statusOf("/health")).isEqualTo(200);
    }

    private int statusOf(String path) throws IOException, InterruptedException {
        return client.send(request(path), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpResponse<String> getBody(String path) throws IOException, InterruptedException {
        return client.send(request(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
    }
}
