package org.maglez.eop.config;

import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the {@code eop.web} namespace: trusted reverse-proxy addresses
 * and per-address rate limits.
 *
 * <p>The trusted-proxies list holds literal IP addresses or CIDR blocks. It is empty
 * by default, which means no peer is believed and {@code X-Forwarded-For} is ignored
 * wherever the application is reached (ADR-021). That default is the whole point:
 * EOP-26 was a bypass of the join throttle that existed precisely because the header
 * was read without asking who sent it, and a default of "trust nobody" cannot be got
 * wrong by forgetting to configure it.
 *
 * <p>The deployed value is set on the {@code app} service in {@code compose.app.yml},
 * next to the fixed address of the reverse proxy it has to match, rather than in
 * {@code application-prod.yml}. A single file then owns both halves of one fact.
 *
 * <p>Entries are parsed and validated when the bean that consumes them is created, so
 * a malformed block fails startup rather than silently narrowing what is trusted. The
 * validation lives with the parsing in the web adapter; there is nothing useful a bean
 * validation annotation could say about a CIDR block here.
 *
 * <p>{@code sessionCreationLimit} is the maximum number of successful session
 * creations one client address may make in a 60-second window before receiving HTTP
 * 429 (EOP-19, ADR-033). The default of 5 is the production limit. Override to
 * {@link Integer#MAX_VALUE} in {@code src/test/resources/application.properties} so
 * the shared Spring context is not exhausted across integration tests.
 *
 * @param trustedProxies      addresses or CIDR blocks whose forwarding headers are believed
 * @param sessionCreationLimit maximum creations per address per 60-second window
 */
@ConfigurationProperties(prefix = "eop.web")
@Validated
public record TrustedProxyProperties(
        @DefaultValue List<String> trustedProxies,
        @DefaultValue("5") @Min(1) int sessionCreationLimit) {

    public TrustedProxyProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
}
