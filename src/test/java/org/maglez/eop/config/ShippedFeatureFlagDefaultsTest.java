package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the shipped defaults of every feature flag that is pinned {@code true} in
 * {@code src/test/resources/application.properties}.
 *
 * <p>The three flags — {@code eop.features.game-over}, {@code eop.features.session-lifecycle}
 * and {@code eop.features.trick-play} — are all pinned to {@code true} in the test-resource
 * override so that the whole suite exercises the live code paths rather than testing the
 * absence of features. That override is correct and must not be removed. But it creates a
 * blind spot: any test that reads the Spring {@link org.springframework.core.env.Environment}
 * will see the test-resource value, not the shipped default, so an assertion like
 * {@code environment.getProperty("eop.features.game-over")} would pass even if the flag were
 * still {@code false} in {@code application.yml}. That is exactly the trap that caused EOP-82:
 * the flag was {@code false} in the shipped YAML while the test suite ran green because the
 * test override masked it.
 *
 * <p>This class therefore reads {@code application.yml} directly from the classpath using
 * {@link YamlPropertiesFactoryBean} — the same Spring API that the application itself uses
 * to bind the file — and asserts the raw value before any test-resource override is applied.
 * SnakeYAML is already on the classpath transitively through {@code spring-boot-starter}, so
 * no additional dependency is needed. The test carries no Spring context ({@code @SpringBootTest}
 * is absent) and runs in sub-milliseconds.
 *
 * <p>The pattern is deliberately different from {@link H2ConsoleAbsentIntegrationTest}, which
 * reads the {@code Environment} and works correctly there because
 * {@code spring.h2.console.enabled} is <em>not</em> pinned in test resources. Copying that
 * pattern here would produce a test that always passes for the wrong reason. A future reader
 * who wants to "simplify" this class into an {@code Environment} lookup should read this
 * comment first.
 *
 * <p>EOP-82 is the incident that motivated this class. The fix flipped {@code game-over} to
 * {@code true} in {@code application.yml}; this test is the regression guard that would have
 * caught the flag still being {@code false} before the fix shipped.
 */
@DisplayName("Shipped feature-flag defaults in application.yml")
class ShippedFeatureFlagDefaultsTest {

    /**
     * Loads {@code application.yml} from the main classpath (not the test classpath) and
     * returns it as a flat {@link Properties} map, with YAML hierarchy flattened to dotted
     * keys exactly as Spring Boot does at startup.
     */
    private static Properties loadShippedYaml() {
        final YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        final Properties props = factory.getObject();
        assertThat(props)
                .as("application.yml must be loadable from the classpath")
                .isNotNull();
        return props;
    }

    @Test
    @DisplayName("eop.features.game-over is true in the shipped application.yml (regression guard for EOP-82)")
    void gameOverFlagIsEnabledInShippedYaml() {
        final Properties props = loadShippedYaml();

        assertThat(props.getProperty("eop.features.game-over"))
                .as("eop.features.game-over must be 'true' in application.yml — "
                        + "if this fails the leaderboard route will return a framework 404 in production")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("eop.features.session-lifecycle is true in the shipped application.yml")
    void sessionLifecycleFlagIsEnabledInShippedYaml() {
        final Properties props = loadShippedYaml();

        assertThat(props.getProperty("eop.features.session-lifecycle"))
                .as("eop.features.session-lifecycle must be 'true' in application.yml — "
                        + "if this fails all session routes will return a framework 404 in production")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("eop.features.trick-play is true in the shipped application.yml")
    void trickPlayFlagIsEnabledInShippedYaml() {
        final Properties props = loadShippedYaml();

        assertThat(props.getProperty("eop.features.trick-play"))
                .as("eop.features.trick-play must be 'true' in application.yml — "
                        + "if this fails the deal, hand, plays and trick routes will return a framework 404 in production")
                .isEqualTo("true");
    }
}
