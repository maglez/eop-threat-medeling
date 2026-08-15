# ADR-005: Error Handling Strategy — RFC 9457 Problem Details

**Status:** Accepted
**Date:** 2026-07-26
**Deciders:** @tech-lead, @architecture-guardian

## Context

The card game needs consistent error responses across all REST endpoints. Without a standard format, each controller may return different error shapes, forcing clients and test agents to handle ad-hoc formats.

Spring Boot provides several error-handling mechanisms: `@ExceptionHandler` per controller, `HandlerExceptionResolver`, `ErrorController`, and `ResponseEntityExceptionHandler`. The chosen approach must:
- Produce consistent, machine-readable error responses
- Work across all controllers without repetition
- Respect Clean Architecture boundaries (domain exceptions must not depend on Spring)

## Decision

- **RFC 9457 Problem Details** (`application/problem+json`) as the standard error response format
- **`@ControllerAdvice`** with `ResponseEntityExceptionHandler` extension as the single global error handler
- Three-layer exception hierarchy:
  - **Domain layer** (`org.maglez.eop.*`): pure Java exceptions (e.g., `IllegalMoveException`, `GameNotFoundException`) — no framework imports
  - **Application layer**: maps domain exceptions to HTTP status codes
  - **Interface layer**: `GlobalExceptionHandler` catches all exceptions and renders RFC 9457 responses
- `@ExceptionHandler` methods in the global handler — not scattered across controllers
- Validation errors (method argument `@Valid`) return a `ValidationProblemDetail` with field-level errors

## Consequences

- **Positive:**
  - All API errors conform to a single, standard format
  - Domain exceptions stay framework-free (Clean Architecture compliance)
  - Test agents can parse errors programmatically
  - Swagger UI documents error schemas automatically via `@ApiResponse`
- **Negative:**
  - `GlobalExceptionHandler` is a single point of failure — a bug there hides all errors
  - RFC 9457 adds a thin wrapper layer between domain exceptions and HTTP responses
- **Mitigation:** Unit tests for `GlobalExceptionHandler` cover every known domain exception → HTTP status mapping

## Related

- [Error Handling Rules](../../.opencode/rules/error-handling.md)
- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
- [ADR-004: API Contract-First](./ADR-004-api-contract-first.md)

> **Extended by [ADR-031](ADR-031-the-score-is-read-through-its-own-route.md) §7.** That section settles what a refusal looks like when the *stored game* contradicts itself rather than the request: one problem type per client-actionable condition, and there is exactly one such condition here — none — so eight domain refusals collapse into a single `ScoreNotDerivableException` answered as a 500 whose body is byte-identical to every other server fault, with the reason and the identifiers going to the log instead. The rule generalises beyond scoring to any read of persisted aggregate state, which is why it is recorded there and pointed at from here.
