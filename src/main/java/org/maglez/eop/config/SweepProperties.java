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

    /**
     * Creates the properties holder with the default interval and delay above.
     *
     * <p>Spring instantiates this reflectively and then applies any {@code eop.sweep.*} values it finds, so the field
     * initialisers are the effective defaults when the namespace is absent entirely.
     */
    public SweepProperties() {
        // Defaults are the field initialisers; Spring overwrites them through the setters where configured.
    }

    /**
     * Returns the interval between sweep runs.
     *
     * @return the interval in milliseconds, never below 1000
     */
    public long getIntervalMs() {
        return intervalMs;
    }

    /**
     * Sets the interval between sweep runs.
     *
     * <p>Called by Spring during binding. The value is validated after binding, so an out-of-range figure fails
     * startup rather than being silently clamped.
     *
     * @param intervalMs the interval in milliseconds; must be at least 1000
     */
    public void setIntervalMs(final long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * Returns the delay before the first sweep run after startup.
     *
     * @return the delay in milliseconds, never negative
     */
    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    /**
     * Sets the delay before the first sweep run after startup.
     *
     * <p>Called by Spring during binding. A delay exists so that a freshly started instance finishes wiring and
     * serving before it begins deleting rows.
     *
     * @param initialDelayMs the delay in milliseconds; must not be negative
     */
    public void setInitialDelayMs(final long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
