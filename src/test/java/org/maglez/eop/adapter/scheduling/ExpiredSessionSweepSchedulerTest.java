package org.maglez.eop.adapter.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.usecase.SweepExpiredSessionsUseCase;

/**
 * Unit tests for {@link ExpiredSessionSweepScheduler}.
 *
 * <p>The scheduler is a thin adapter: it delegates entirely to
 * {@link SweepExpiredSessionsUseCase}. The only contract to verify is that
 * the scheduled method calls {@code execute()} on the use case.
 */
@DisplayName("ExpiredSessionSweepScheduler")
class ExpiredSessionSweepSchedulerTest {

    private final SweepExpiredSessionsUseCase sweepUseCase = mock(SweepExpiredSessionsUseCase.class);
    private final ExpiredSessionSweepScheduler scheduler = new ExpiredSessionSweepScheduler(sweepUseCase);

    @Test
    @DisplayName("delegates to SweepExpiredSessionsUseCase when triggered")
    void shouldDelegateToUseCase() {
        scheduler.sweepExpiredSessions();

        verify(sweepUseCase).execute();
    }
}
