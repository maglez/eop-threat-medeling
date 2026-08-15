package org.maglez.eop.usecase;

/**
 * Thrown when a session already has the maximum number of SSE subscribers.
 *
 * <p>Mapped to HTTP 429 by {@code GlobalExceptionHandler}. The cap is 12 per session
 * (2× MAXIMUM_PLAYERS to allow reconnect churn) and 500 globally (EOP-20, ADR-034).
 */
public final class TooManySubscribersException extends RuntimeException {

    public TooManySubscribersException(final String message) {
        super(message);
    }
}
