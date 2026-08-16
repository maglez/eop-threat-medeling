package org.maglez.eop.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the expired-session sweep scheduler.
 *
 * <p>Properties are bound from the {@code eop.sweep} namespace in
 * {@code application.yml}. The scheduler reads these values at startup;
 * changing them requires a restart.
 *
 * <p>This class is an infrastructure toggle, not a feature flag. It belongs
 * directly under {@code eop.*} rather than {@code eop.features.*} — see
 * {@code feature-flags.md} and {@code configuration.md}.
 */
@Validated
@ConfigurationProperties(prefix = "eop.sweep")
public class SweepProperties {

    /**
     * Interval between sweep runs in milliseconds.
     * Defaults to 3 600 000 ms (1 hour).
     */
    @Min(value = 1000, message = "sweep interval must be at least 1000 ms")
    private long intervalMs = 3_600_000L;

    /**
     * Initial delay before the first sweep run in milliseconds.
     * Defaults to 300 000 ms (5 minutes).
     */
    @Min(value = 0, message = "sweep initial delay must be non-negative")
    private long initialDelayMs = 300_000L;

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(final long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(final long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
