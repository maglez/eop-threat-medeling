package org.maglez.eop.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which peers may tell this application who its client is.
 *
 * <p>The list holds literal IP addresses or CIDR blocks. It is empty by default, which
 * means no peer is believed and {@code X-Forwarded-For} is ignored wherever the application
 * is reached (ADR-021). That default is the whole point: EOP-26 was a bypass of the join
 * throttle that existed precisely because the header was read without asking who sent it,
 * and a default of "trust nobody" cannot be got wrong by forgetting to configure it.
 *
 * <p>The deployed value is set on the {@code app} service in {@code compose.app.yml}, next
 * to the fixed address of the reverse proxy it has to match, rather than in
 * {@code application-prod.yml}. A single file then owns both halves of one fact.
 *
 * <p>Entries are parsed and validated when the bean that consumes them is created, so a
 * malformed block fails startup rather than silently narrowing what is trusted. The
 * validation lives with the parsing in the web adapter; there is nothing useful a bean
 * validation annotation could say about a CIDR block here.
 *
 * @param trustedProxies addresses or CIDR blocks whose forwarding headers are believed
 */
@ConfigurationProperties(prefix = "eop.web")
public record TrustedProxyProperties(@DefaultValue List<String> trustedProxies) {

    public TrustedProxyProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
}
