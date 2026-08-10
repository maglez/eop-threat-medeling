package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Proves the H2 console is absent, and stays absent by accident as well as by intent (EOP-27).
 *
 * <p>The console is unauthenticated arbitrary SQL against the running application's own
 * database, and it accepts a JDBC URL of the caller's choosing, which is the shape of
 * CVE-2021-42392. There is no Spring Security dependency in this project that could stand
 * in front of it. {@code application.yml} nonetheless said {@code enabled: true}, on the
 * profile every developer run uses, so the intent recorded in configuration was to expose it.
 *
 * <p>It was never actually exposed. Spring Boot 4 moved the console's autoconfiguration out
 * of {@code spring-boot-autoconfigure} into a separate {@code spring-boot-h2console} module,
 * and nothing here depends on that module, so no class on the classpath ever read the
 * property. The defect this class guards is therefore not a live endpoint but a standing
 * {@code yes} in configuration that only needed a dependency to become one.
 *
 * <p>Which changes what the assertions are worth, and the honest reading is worth stating.
 * {@link #shouldNotHaveTheConsoleAutoconfigurationOnTheClasspath()} is the only one that can
 * fail today: it is the tripwire, and it fires the moment somebody adds the module, so the
 * decision gets made at review time rather than discovered in an incident. The other three
 * cannot fail while the module is absent — nothing exists that could serve a console, so they
 * are vacuous by construction. They are kept anyway, because the tripwire converts them: the
 * day it fires and somebody adds the module deliberately, these become the assertions that
 * the guard in {@code application.yml} actually holds, and they are already in place rather
 * than needing to be remembered under pressure.
 *
 * <p>The server is real and the request goes over a socket, because the console is a servlet
 * registration rather than a Spring MVC handler mapping: {@code MockMvc} answers 404 for
 * {@code /h2-console} whether a console is registered or not, so a MockMvc version of this
 * test would pass for the wrong reason forever. The client is the JDK's own
 * {@code HttpClient}, matching {@code SessionStreamIntegrationTest} — the project has no
 * WebFlux dependency and therefore no {@code WebClient}.
 *
 * <p>The test-scoped override that used to pin {@code spring.h2.console.enabled=false} in
 * {@code src/test/resources/application.properties} has been removed, so this suite reads
 * the shipped default it claims to be testing rather than one of its own making.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("H2 console on the shipped configuration")
class H2ConsoleAbsentIntegrationTest {

    /**
     * The autoconfiguration class that would register the console. Spring Boot 4 ships it in
     * {@code org.springframework.boot:spring-boot-h2console}, which this project does not depend on.
     */
    private static final String CONSOLE_AUTOCONFIGURATION =
            "org.springframework.boot.h2console.autoconfigure.H2ConsoleAutoConfiguration";

    /** The bean name that autoconfiguration registers the console servlet under, when it is present. */
    private static final String CONSOLE_BEAN = "h2Console";

    /** The property that autoconfiguration consults, and the shipped default this pins. */
    private static final String CONSOLE_ENABLED = "spring.h2.console.enabled";

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void hangUp() {
        client.shutdownNow();
    }

    @Test
    @DisplayName("tripwire: if this fails, the console module has been added — re-read the guard in application.yml first")
    void shouldNotHaveTheConsoleAutoconfigurationOnTheClasspath() {
        assertThatExceptionOfType(ClassNotFoundException.class)
                .isThrownBy(() -> Class.forName(CONSOLE_AUTOCONFIGURATION));
    }

    @Test
    @DisplayName("resolves the shipped default to false, so flipping it back on breaks this build")
    void shouldResolveTheShippedDefaultToFalse() {
        assertThat(environment.getProperty(CONSOLE_ENABLED, Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("does not register the console servlet: the mechanism, not just the route")
    void shouldNotRegisterTheConsoleServlet() {
        assertThat(context.containsBean(CONSOLE_BEAN)).isFalse();
    }

    @Test
    @DisplayName("does not serve the console path over real HTTP, with or without a trailing slash")
    void shouldNotServeTheConsole() throws Exception {
        assertThat(statusOf("/h2-console")).isEqualTo(404);
        assertThat(statusOf("/h2-console/")).isEqualTo(404);
    }

    private int statusOf(final String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
