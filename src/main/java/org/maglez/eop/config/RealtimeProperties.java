package org.maglez.eop.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the event stream.
 *
 * <p>Typed and validated rather than read as a raw string, per ADR-013. The
 * distinction from the session-lifecycle feature flag is worth stating: a flag that
 * decides whether a bean exists must be read from the {@code Environment} before
 * binding happens, so it cannot live here. This value is read at runtime, by code
 * that is already running, which is exactly what this idiom suits.
 *
 * <p>Bound at startup, so changing the interval restarts the application.
 *
 * @param heartbeatInterval how often a comment frame is written to every open
 *                          stream. Not a tuning knob for load: without it a silently
 *                          dead connection is indistinguishable from an idle one, and
 *                          the EOP-8 spike watched the server report two live
 *                          subscribers after both clients had gone away (ADR-014).
 *                          Fifteen seconds is comfortably inside the sixty-second
 *                          idle timeout that proxies and load balancers commonly
 *                          apply, and cheap enough at a handful of tables that the
 *                          cost is not worth reasoning about.
 */
@ConfigurationProperties(prefix = "eop.realtime")
@Validated
public record RealtimeProperties(@NotNull @DefaultValue("15s") Duration heartbeatInterval) {

    /**
     * Rejects a non-positive interval.
     *
     * <p>A zero or negative interval would schedule a task that either never runs or
     * runs continuously. Both are worse than the default, and neither is a plausible
     * intention, so this fails at startup rather than at three in the morning.
     *
     * @throws IllegalArgumentException if the interval is not positive
     */
    public RealtimeProperties {
        if (heartbeatInterval != null && (heartbeatInterval.isZero() || heartbeatInterval.isNegative())) {
            throw new IllegalArgumentException("eop.realtime.heartbeat-interval must be positive");
        }
    }
}
