package org.maglez.eop.adapter.scheduling;

import java.util.Objects;
import org.maglez.eop.usecase.SweepExpiredSessionsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger that invokes the session-expiry sweep on a fixed interval.
 *
 * <p>This adapter is the framework driver for
 * {@link SweepExpiredSessionsUseCase}. It holds no business logic — it only
 * translates a Spring scheduling tick into a use-case call.
 *
 * <p>This bean is gated on {@code eop.features.session-lifecycle} so that it is
 * only active when the session lifecycle feature is enabled. While the flag is
 * {@code false} the bean does not exist and no scheduled task is registered.
 *
 * <p>The sweep runs every hour by default. The interval is intentionally coarser
 * than the 24-hour TTL: a session that expired at 14:00 may not be deleted until
 * 15:00, which is acceptable. The sweep is not a real-time revocation mechanism —
 * {@link org.maglez.eop.usecase.ResolvePlayerUseCase} is the real-time guard.
 */
@Component
@ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
public class ExpiredSessionSweepScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredSessionSweepScheduler.class);

    private final SweepExpiredSessionsUseCase sweepUseCase;

    /**
     * Creates the scheduler.
     *
     * @param sweepUseCase the use case that performs the sweep
     */
    public ExpiredSessionSweepScheduler(final SweepExpiredSessionsUseCase sweepUseCase) {
        this.sweepUseCase = Objects.requireNonNull(sweepUseCase, "sweepUseCase is required");
    }

    /**
     * Triggers the expired-session sweep.
     *
     * <p>Runs every hour (3 600 000 ms) by default, configurable via
     * {@code eop.sweep.interval-ms}. The initial delay of 5 minutes prevents the
     * sweep from running immediately on startup.
     *
     * <p>Note: {@code @Scheduled} annotation attributes must be compile-time constants,
     * so the interval and initial-delay values are read here as raw SpEL placeholders
     * rather than through the {@link org.maglez.eop.config.SweepProperties} bean.
     * {@code SweepProperties} still exists and is validated at startup (via
     * {@code @ConfigurationPropertiesScan} + {@code @Validated}), acting as the
     * startup guard that rejects invalid values (e.g. a zero or negative interval)
     * before the application finishes starting. The two bindings share the same
     * {@code eop.sweep.*} keys and defaults; keep them in sync if either changes.
     */
    @Scheduled(fixedDelayString = "${eop.sweep.interval-ms:3600000}",
               initialDelayString = "${eop.sweep.initial-delay-ms:300000}")
    public void sweepExpiredSessions() {
        LOG.debug("Session sweep: triggered by scheduler");
        sweepUseCase.execute();
    }
}
