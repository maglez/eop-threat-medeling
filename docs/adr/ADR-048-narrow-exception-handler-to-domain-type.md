# ADR-048: Narrow the IllegalArgumentException handler to a domain type, preserving persistence exception translation

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** operator, primary agent (`MODEL_A`)

## Context

`GlobalExceptionHandler` carried `@ExceptionHandler(IllegalArgumentException.class)` returning
`problem.setDetail(exception.getMessage())` with status 400 and title `"Invalid request"`. Its
Javadoc justified echoing the message on the grounds that such exceptions "are raised by our own
guard clauses with text written for a caller, not by a library with text written for a maintainer"
— an assumption about the codebase, not an enforced invariant. `IllegalArgumentException` is
thrown pervasively by the JDK, Hibernate, Jackson and Spring, and `NumberFormatException` extends it.

Two consequences. (1) **Information disclosure:** any library-internal message echoed verbatim. (2)
**The more important one:** because that mapping was *more specific* than
`handleUnexpected(Exception)`, such a fault never reached the single `LOG.error` in the class — an
internal defect was reported to the caller as a client-side 400 and left no record at all. That is
the exact inverse of ADR-005's unmapped exceptions falling through to a 500 *with* a stack trace.

It was latent, not live. An audit of all 60 `throw new IllegalArgumentException` sites found only
three types have a caller-reachable guard: `PageQuery` (all 3 throws, reached from `CardController`
`@RequestParam` page/size which carry no bean validation), `DisplayName` (control characters —
`@NotBlank`/`@Size` cover the other two), and `TrickPlay` (blank component name, control chars,
bidi formatting — `@Size(max=n)` admits `""` and `"   "`). **The defect was the absence of the
invariant, not any observed response.**

## Decision

1. **Mint `InvalidInputException` in `org.maglez.eop.entity`.** It extends
   `IllegalArgumentException`, deliberately inverting the house pattern where all 35 other domain
   exceptions extend `RuntimeException` directly.

2. **Narrow the handler.** Replace `@ExceptionHandler(IllegalArgumentException.class)` with
   `@ExceptionHandler(InvalidInputException.class)` → `handleInvalidInput`, keeping status 400 and
   title `"Invalid request"` unchanged. Add `LOG.warn("Rejected caller input: {}",
   exception.getMessage())` on that branch.

3. **Convert caller-facing guards.** The following sites now throw `InvalidInputException`:
   - `PageQuery`: all three (`page < 0`, `size < 1`, `size > MAX_SIZE`)
   - `DisplayName`: all three (blank, over-length, control characters)
   - `TrickPlay`: six (component count, blank component name, component name length, notes length,
     and both throws in `rejectUnsafeText`)

4. **Leave internal guards unchanged.** The following now become logged 500s instead of silent 400s:
   - `TrickPlay.seatOrder` range guard (bounds a credential-resolved value, not caller input)
   - Every other site: `Hands`, `Trick`, `GameSession`, `Player`, `Card`, `Rank`, `Hand`,
     `Standing`, `ScoredPlay`, `GameResult`, `IdentityTokenHash`, `JoinCode`, `PageResult`,
     `RealtimeProperties`, `InMemorySessionCreationLimiter`

5. **Preserve one JDK throw.** `TrickPlayRepositoryAdapter.seatToWrite` keeps the JDK type because
   converting it to a type imported from `org.maglez.eop.entity` would add an eleventh origin to the
   set `TrickPlayExceptionOriginTest.java` derives from that adapter's source text, and its
   `numberWord` helper stops at ten.

6. **Assert the invariant programmatically.** Add a top-level reflection test
   `shouldMapNoJdkExceptionTypeApartFromTheCatchAll()` in `GlobalExceptionHandlerTest` that walks
   `GlobalExceptionHandler.class.getDeclaredMethods()`, collects every `@ExceptionHandler` value,
   and fails if any is a `java.*` type other than `Exception` itself.

### Why extending `IllegalArgumentException` is load-bearing

The persistence adapters (`TrickPlayRepositoryAdapter`, `SessionRepositoryAdapter`,
`CardRepositoryAdapter`) are `@Repository` beans, so Spring's persistence exception translation
rewrites an `IllegalArgumentException` raised while reconstituting an entity from a row into
`InvalidDataAccessApiUsageException` (a `DataAccessException`), which reaches the catch-all as the
logged 500 a corrupt row deserves. Three integration tests already pin this, one asserting
`.isNotInstanceOf(IllegalArgumentException.class)` explicitly (`TrickPlayRepositoryAdapterIntegrationTest:356-366`,
also `:333`, `:850`, `:877`; `SessionRepositoryAdapterIntegrationTest:363-371,398`). A value
object cannot tell whether it is being constructed from a request or from a row. Had the new type
extended `RuntimeException` it would have escaped translation untouched, and a corrupt row would
have been echoed to the caller as a 400 carrying a database-derived message — the very defect
EOP-28 exists to close, merely relocated. Extending the JDK type also keeps 16 value-object unit
test files and `PlayCardUseCaseTest`'s over-long-notes case asserting `IllegalArgumentException`
with zero churn.

### Rejected alternatives

- **Marker interface.** No interface of any kind exists under `entity/` today and a marker would
  need `@ExceptionHandler` to name a concrete type anyway.
- **Extending `RuntimeException`.** Rejected for the translation reason above. This is the
  alternative most likely to be "helpfully restored" later, so the reasoning is prominent here.
- **`@Validated` + `@Min`/`@Max` on `CardController`.** Rejected because it changes the response
  body shape and breaks `CardControllerIntegrationTest:97`'s `$.detail` assertion, and
  `CardController:44-46` documents the explicit-rejection choice deliberately.

## Consequences

**Accepted gains.**

- **Information disclosure closed.** Library-internal messages are no longer echoed to callers.
- **Internal defects are now logged.** An `IllegalArgumentException` from a `@Repository` reaches
  `LOG.error` as a 500, not a silent 400.
- **The invariant is enforced.** The reflection test catches a future handler that accidentally
  names a JDK type.
- **Translation is preserved.** Spring's persistence exception translation continues to work as
  designed, converting repository-layer `IllegalArgumentException` into `DataAccessException`.

**Accepted costs, stated plainly.**

- **The 400-vs-500 outcome for the same exception type now depends on whether the throw happened
  inside a `@Repository`.** That is implicit rather than explicit. It is not newly invented here —
  it is the codebase's existing, tested mechanism — but it is a real cost and this ADR records it
  rather than presenting the design as free.
- **The reflection test has a known limit.** It detects a handler that names a JDK type, but it
  does not detect the opposite mistake: a *new* caller-input guard written with a bare
  `IllegalArgumentException`, which would silently become a 500. That remains a review matter.
- **One guard stays on the JDK type.** `TrickPlayRepositoryAdapter.seatToWrite` is coupled to
  `TrickPlayExceptionOriginTest`'s `numberWord` helper, and decoupling it would require amending
  that test or the ADR that pins it (ADR-023).
- **Sixteen value-object test files and one use-case test assert `IllegalArgumentException`.**
  They continue to pass because `InvalidInputException extends IllegalArgumentException`, but the
  coupling is visible and any future refactoring that changes the exception hierarchy will need to
  update these assertions.

## Related

- [ADR-005](ADR-005-error-handling-strategy.md) — the original error handling strategy, whose
  catch-all `LOG.error` this decision makes reachable for `IllegalArgumentException`
- [ADR-006](ADR-006-build-quality-gates.md) — the build gates, including the documentation-integrity
  tests that verify this ADR's index entry
- `.opencode/rules/error-handling.md` — the rule file this ADR does **not** amend, because the rule
  already says "domain exception → HTTP status" and the new type is a domain exception; the rule
  does not prescribe the exception's superclass
- `GlobalExceptionHandler.java` — the class this decision modifies
- `GlobalExceptionHandlerTest.java` — the test class that gains the reflection invariant
- `InvalidInputException.java` — the new domain exception
- EOP-28 (this decision)