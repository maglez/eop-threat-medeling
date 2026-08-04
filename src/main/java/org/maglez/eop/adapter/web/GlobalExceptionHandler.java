package org.maglez.eop.adapter.web;

import org.maglez.eop.entity.CardNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The application's single error handler.
 *
 * <p>Every 4xx and 5xx response the API produces is rendered here as RFC 9457
 * problem details (ADR-005). There is deliberately one handler and not one per
 * controller: a client should never have to discover that two endpoints report
 * failure in two different shapes.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} inherits Spring's handling
 * of framework-level failures — an unparseable body, an unsupported media type, a
 * path variable that is not a UUID — and those already emit problem details, so
 * they are not re-implemented below.
 *
 * <p>The mapping is deliberately narrow. Domain exceptions carry no HTTP
 * vocabulary, so this class is the only place that decides a status code, and
 * every mapping here has a unit test as the error handling rules require.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * A named card does not exist.
     *
     * @param exception the domain exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(CardNotFoundException.class)
    public ProblemDetail handleCardNotFound(final CardNotFoundException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Card not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The request asked for something the domain refuses to represent — an
     * out-of-range page, a rank that does not exist. The message is safe to
     * return because these exceptions are raised by our own guard clauses with
     * text written for a caller, not by a library with text written for a
     * maintainer.
     *
     * @param exception the rejected argument
     * @return a 400 problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * Anything unanticipated.
     *
     * <p>The cause is logged and deliberately not returned. An unexpected failure
     * is the one case where the message is most likely to describe internals, and
     * the application is deployed on a public address with no authentication, so
     * the response says nothing a caller could use to map the inside of the
     * system.
     *
     * @param exception the unexpected failure
     * @return a 500 problem detail with no internal information
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception exception) {
        LOG.error("Unhandled exception serving a request", exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }
}
