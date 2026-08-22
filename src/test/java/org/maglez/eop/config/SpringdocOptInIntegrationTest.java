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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Proves the springdoc opt-in still works, so "disabled by default" cannot mean "broken" (EOP-38, ADR-049).
 *
 * <p>This is the second half of a pair and it exists because of a specific failure mode.
 * {@link SpringdocDisabledByDefaultIntegrationTest} asserts three endpoints answer 404, and
 * that assertion is satisfied just as well by a springdoc that has been accidentally destroyed
 * — a bad dependency exclusion, a removed starter, a broken annotation scan — as by the
 * deliberate default this story shipped. A suite that can only observe absence cannot tell a
 * working control from a broken feature. This class removes that ambiguity by turning the
 * control off and requiring the schema to come back.
 *
 * <p>It also pins the property that makes the design legitimate rather than merely strict.
 * ADR-047 recorded, while declining an enforcer ban, that "a ban with no sanctioned escape
 * hatch trains people to disable bans"; a control must be satisfiable by whoever has a real
 * reason to satisfy it. Here the escape hatch is two environment variables carried by the
 * developer shell — {@code SPRINGDOC_APIDOCS_ENABLED} and {@code SPRINGDOC_SWAGGERUI_ENABLED},
 * both in {@code .env.example}, which {@code .envrc} exports via {@code dotenv}. This class
 * exercises the same two properties those variables bind to through Spring's relaxed binding,
 * verified by hand against a running application before being written down. If a future change
 * makes the opt-in unreachable, that is a defect in the escape hatch and this test fails,
 * rather than the loss going unnoticed because everything still looks locked down.
 *
 * <p>The container is unaffected by this opt-in and it matters that the reason is structural
 * rather than a matter of care: {@code compose.app.yml} declares no {@code env_file:}, so it
 * interpolates {@code .env} into the Compose document and never injects it into the running
 * container. On top of that {@code application-prod.yml} still pins both keys to {@code false}
 * as an independent second guard, so even a deployed environment that did export these
 * variables would continue to serve nothing. Neither property is observed here.
 *
 * <p>The {@code properties} attribute below deliberately sets the canonical property names
 * rather than the environment variables. A test cannot portably mutate its own process
 * environment, and asserting on the relaxed-binding spelling would be testing Spring's
 * property relaxation rather than this application's behaviour. The variable names are the
 * documented developer-facing surface; the properties are what the application reads.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@DisplayName("springdoc when a developer opts in")
class SpringdocOptInIntegrationTest {

    private static final String API_DOCS = "/v3/api-docs";

    private static final String SWAGGER_UI = "/swagger-ui/index.html";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void hangUp() {
        client.shutdownNow();
    }

    @Test
    @DisplayName("serves the OpenAPI document again")
    void shouldServeTheApiDocument() throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request(API_DOCS), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"openapi\"");
    }

    @Test
    @DisplayName("serves the Swagger UI again")
    void shouldServeSwaggerUi() throws IOException, InterruptedException {
        int status = client.send(request(SWAGGER_UI), HttpResponse.BodyHandlers.discarding()).statusCode();

        assertThat(status).isEqualTo(200);
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
    }
}
