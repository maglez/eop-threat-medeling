# ADR-054: The OpenAPI contract is a build gate — the generated springdoc document is compared against the hand-authored file

**Status:** Accepted

**Date:** 2026-08-23

**Deciders:** @architecture-guardian, @tech-lead

## Context

The project is contract-first by rule (`.opencode/rules/api-design.md`: hand-author `docs/api/openapi.yml` before implementing endpoints). EOP-72 changed observable dealing behaviour (all 68 cards dealt → floor(D/n)×n with remainder discarded) without amending `docs/api/openapi.yml`, which went on documenting the full deal **correctly** — so the spec was right and the *implementation* drifted. It went undetected for a whole story, with a green build. Found by manual audit (EOP-83 divergence 1); EOP-92 resolved it in favour of the spec by changing the code back. Three documents were simultaneously stale with a green build: `docs/api/openapi.yml`, `docs/adr/README.md`'s ADR-023 row, and `docs/architecture/C4-Diagrams.md`'s "POST /deal … facilitator deals the whole deck" row. @architecture-guardian raised this ADR during EOP-92 sign-off, noting the Documentation Gate had been the only thing between a stale corpus and `main` on two consecutive stories (EOP-82, EOP-92).

The ticket asked to investigate three options: (a) CI requiring a commit touching a controller/use case/DTO to also touch `openapi.yml`; (b) diffing the **generated** springdoc document against the hand-authored file; (c) a PR-template checklist item.

## Decision

### Option (b) is adopted as a JUnit build gate

A new test `src/test/java/org/maglez/eop/docs/OpenApiContractDriftTest.java` (410 lines) joins the six existing documentation-integrity guards in that package. Unlike all of them it boots a Spring context, because the generated document only exists at runtime.

The mechanism:

1. **`@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})`** — springdoc is disabled by default in the shipped configuration (ADR-049), so the guard opts it in for itself only. The array is byte-identical to `SpringdocOptInIntegrationTest`'s so Spring's `MergedContextConfiguration` cache key matches and the context is shared.

2. **Fetches `/v3/api-docs`** with `java.net.http.HttpClient`, parses with Jackson. Because springdoc builds the document from Spring's own `RequestMappingHandlerMapping`, the generated side cannot disagree with what Spring actually serves.

3. **Parses the authored `docs/api/openapi.yml` by indentation with regexes**, deliberately matching `EnumMirrorParityTest`'s house style, so inconsistent indentation or an unquoted status key fails here too. The parse is bounded to the `paths:` block (start `^paths:\s*$`, stop at the next top-level `^\S` key) so three-digit keys under `components:` cannot false-positive.

4. **Compares two axes, bidirectionally:**
   - (1) the path+method operation set
   - (2) the declared response status codes per operation

   Four tests:
   - `shouldDocumentEveryOperationSpringServes` — every operation Spring serves is documented
   - `shouldServeEveryDocumentedOperation` — every documented operation is reachable
   - `shouldAgreeOnDeclaredResponseStatuses` — collects one line per drifting operation and asserts the list empty, so all drift is reported in one run
   - `shouldNotCarryAnUnnecessaryException` — the `PROXY_PRODUCED` section holds exactly one entry: authored `'413'` on `POST /api/v1/sessions`, because that limit is enforced by the reverse proxy (`ui/Caddyfile` sets `request_body { max_size 16KB }` on `/api/*`) and the response is plain text or empty, not a problem detail — so no truthful `@ApiResponse` can exist and springdoc can never emit it

5. **No allow-list and no path exclusions.** The whole surface Spring serves is compared, including `GET /health` on `Main.java`. springdoc reports 17 operations with no framework noise — notably no `/error` path.

### What the mechanism does NOT catch

This section states plainly what the gate does not detect, per acceptance criterion 1:

1. **Prose drift — so it would NOT have caught EOP-72, the very defect that prompted the story.** When dealing behaviour changed, no path, no method and no status code moved; the drift was in a *description*. Both axes stay green. This is the most important limitation and the reason the ticket asked for it to be stated explicitly rather than softened.

2. **Schema and property drift.** Request/response body shapes are not compared at all. Deliberate: springdoc and the hand-authored file legitimately differ on `$ref` naming, nullability, examples and descriptions, and a noisy gate gets deleted rather than obeyed.

3. **Reachability.** It never checks that a documented status is actually *producible* — only that code and contract agree it is declared. Both can be wrong together.

4. **Anything the proxy layer contributes** beyond the one declared `413`.

5. **Parameters, headers, security schemes, content types and tags are not compared.**

### Why options (a) and (c) were rejected

**(a) CI requiring a controller/use-case/DTO commit to also touch `openapi.yml`** fires on pure refactors, renames and comment edits, cannot tell a behaviour change from a formatting change, and is trivially satisfied by a whitespace edit to the YAML — it would train people to make a token edit rather than a correct one, and it catches nothing the four tests do not.

**(c) PR-template checklist item:** three documents were missed on this very story while checklists existed; a checklist item is exactly the "resolved by a promise" outcome criterion 3 forbids as the sole remedy. It is not adopted as the mechanism.

### Code changes the gate forced

Every authored 429 was traced to a real control before annotating, so no lie was needed to make the gate green:

- `SessionController`'s five methods carried `@Operation` but **no `@ApiResponse` at all**, so springdoc collapsed every one of them to a bare `200` — including `POST /api/v1/sessions`, which actually returns `201 Created`. Now annotated truthfully: `createSession` 201/400/429/503 (**not** 413), `joinSession` 200/400/404/409/429, `getSessionState` 200/400/403/404/429, `startSession` 200/400/403/404/409 (no 429 — the read limiter counts GET/HEAD only), `streamSessionEvents` 200/400/403/404/429 (the 429 being the per-session SSE subscriber cap of `TooManySubscribersException`, ADR-034 — *not* the read limiter, which explicitly excludes `/api/v1/sessions/*/events`).

- The `429` from ADR-051's read-route limiter was authored in the contract but missing from the annotations on every GET: added to `CardController` (both methods), `TrickController` (`hand`, `tricks/current`), `ScoreController` (`score`), `GameOverController` (`leaderboard`).

- `Main.health()` gained `@Operation` + `@ApiResponse(200)`, and `/health` plus a `health` tag were authored in `openapi.yml` (200 only, `text/plain`, `enum: [OK]`) — precisely so no exclusion was needed.

The four distinct 429 sources: read-route limiter (ADR-051), session creation limiter (ADR-033), join attempt limiter, and SSE subscriber cap (ADR-034) — three distinct 429 handlers in `GlobalExceptionHandler`.

### Proof it fails on drift

Four experiments, all captured in `.tmp/eop-95-drift-evidence.md` for the PR body:

- **A:** removed the `429` `@ApiResponse` from `CardController.listCards` → `shouldAgreeOnDeclaredResponseStatuses` failed: `Expecting empty but was: ["GET /api/v1/cards: documented but not declared in code [429], declared in code but not documented []"]`.

- **B:** deleted the `/health` path from `openapi.yml` → `shouldDocumentEveryOperationSpringServes` failed: `Expecting empty but was: [GET /health]`.

- **D:** authored a phantom `/api/v1/phantom` path → `shouldServeEveryDocumentedOperation` failed: `Expecting empty but was: [GET /api/v1/phantom]`.

- **C:** added a bogus `PROXY_PRODUCED` entry claiming `200` on `GET /api/v1/cards` → **two** failures, `shouldNotCarryAnUnnecessaryException` *and* `shouldAgreeOnDeclaredResponseStatuses`. A dishonest declared exception does not silence drift, it relocates and doubles the failure.

Each experiment was reverted; `./mvnw verify` is green on the whole tree (BUILD SUCCESS, 49.8 s, SpotBugs 0, JaCoCo all checks met, 147 classes).

## Consequences

- **Positive:** The contract-first rule now has a mechanical enforcement. Path, method and response-status drift is caught at build time rather than by manual audit.
- **Positive:** The gate is bidirectional — it catches both implementation drift *and* stale documentation.
- **Positive:** The `PROXY_PRODUCED` mechanism documents exactly one case where the contract must diverge from what the application declares, and the test fails in both directions if that case changes.
- **Negative — prose drift is undetected.** The gate compares paths, methods and status codes only. A description change like EOP-72's dealing behaviour passes silently. This is the explicit non-catch stated in the Decision section and is the reason the ticket asked for it to be recorded plainly.
- **Negative — schema drift is undetected.** Body shapes, parameter details and metadata are not compared.
- **Negative — the gate boots a Spring context.** Unlike the five other documentation-integrity guards, this one is not a pure text parse. It is necessary because the generated document only exists at runtime, but it makes the test slower and more fragile than the others.
- **Negative — the gate required truthful annotations.** Several controllers were missing `@ApiResponse` entirely, so springdoc was under-reporting what the application actually returns. The gate forced those to be added, which is correct — but it means the gate's first effect was to fix the code it gates, not to catch drift.
- **The header comment in `docs/api/openapi.yml` is amended** to record that the generated document is also the input to a build gate, not merely "for convenience".

## Related

- [ADR-004: API contract-first with OpenAPI 3.1](./ADR-004-api-contract-first.md) — the rule this ADR enforces
- [ADR-049: Springdoc disabled by default](./ADR-049-springdoc-disabled-by-default.md) — why springdoc is opt-in, and why the gate enables it for itself only
- [ADR-006: Build quality gates](./ADR-006-build-quality-gates.md) — the documentation-integrity guard posture this extends
- `.opencode/rules/api-design.md` — the rule that mandates contract-first
- `.opencode/rules/build-quality.md` — lists the six (now seven) documentation-integrity guards
- EOP-95 — the ticket