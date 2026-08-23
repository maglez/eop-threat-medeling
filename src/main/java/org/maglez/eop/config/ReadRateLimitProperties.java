package org.maglez.eop.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the {@code eop.web.read-rate-limit} namespace: the per-address
 * request budget applied to read routes under {@code /api/v1} (EOP-88, ADR-051).
 *
 * <p>This is a separate record rather than two more components on
 * {@link TrustedProxyProperties} deliberately. That record is named for the trusted-proxy
 * allow-list and already carries {@code sessionCreationLimit} against that name; a third
 * unrelated limit would make the name actively misleading. The two records bind sibling
 * prefixes, so nothing about the property names suffers for the split.
 *
 * <p>{@code limit} is the number of read requests one client address may make in a
 * 60-second window before receiving HTTP 429. The default of 300 is sized off the busiest
 * legitimate pattern the front end produces: the SSE doorbell (ADR-014) makes every client
 * re-fetch state on each event, and a three-player game generates roughly 66 trick events,
 * each driving about two reads per client. A household behind one NAT therefore peaks near
 * 100 reads a minute, so 300 leaves headroom of about three times observed peak load while
 * still bounding the amplification EOP-88 describes.
 *
 * <p>{@code maxTrackedKeys} bounds the limiter's own memory. The table fails closed when it
 * is full (ADR-033): a flood of distinct keys is itself an attack pattern, and admitting
 * requests that cannot be counted would let an attacker bypass the limiter by exhausting the
 * table first. This is also why the limiter key is a resolved client address rather than a
 * caller-supplied token — see {@code ReadRateLimitInterceptor}.
 *
 * <p>Both values are raised for the test suite in
 * {@code src/test/resources/application.properties}, because the whole suite shares one
 * client address ({@code 127.0.0.1}) and one Spring context. The tests that exercise
 * refusal lower {@code limit} with {@code @SpringBootTest(properties = ...)} and
 * {@code @DirtiesContext}, never by forging the key — forging it was the EOP-26
 * vulnerability, not a test fixture (ADR-021).
 *
 * @param limit          maximum read requests per client address per 60-second window
 * @param maxTrackedKeys maximum distinct addresses tracked before the limiter refuses new keys
 */
@ConfigurationProperties(prefix = "eop.web.read-rate-limit")
@Validated
public record ReadRateLimitProperties(
        @DefaultValue("300") @Min(1) int limit,
        @DefaultValue("10000") @Min(1) int maxTrackedKeys) {
}
