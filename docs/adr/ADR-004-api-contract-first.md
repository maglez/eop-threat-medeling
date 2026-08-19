# ADR-004: API Contract-First with OpenAPI

**Status:** Accepted (amended 2026-08-19)
**Date:** 2026-07-26
**Deciders:** @tech-lead, @architecture-guardian

## Context

The EoP card game exposes REST endpoints for game creation, card drawing, threat category management, and privilege escalation. Multiple agents (frontend, backend, API testers) need a shared contract to avoid integration mismatches.

Without a contract-first approach, each agent works from implicit assumptions, leading to:
- Mismatched request/response shapes between agents
- Inconsistent URL conventions and error formats
- Manual documentation that drifts from implementation

## Decision

- **Contract-first** with OpenAPI 3.1 as the source of truth
- **`springdoc-openapi-starter-webmvc-ui`** for runtime spec generation at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`
- OpenAPI spec files stored in **`docs/api/openapi.yml`** and hand-authored (spec-first) for new endpoints before implementation
- Controllers annotated with `@Operation`, `@ApiResponse` for documentation
- **URL convention:** `/api/v1/{resource}` — versioned from day one
- Standard collection responses: paginated (`Page<T>`) for lists, `200`/`201`/`204` for mutations
- No `@RequestMapping` at class level on interfaces — concrete controllers only (avoids AOP proxy confusion)

## Consequences

- **Positive:**
  - Single source of truth for all agents building or testing API endpoints
  - Auto-generated Swagger UI for manual testing during development
  - Clear versioning path without breaking existing consumers
- **Negative:**
  - Spec drift if engineers forget to update `docs/api/openapi.yml` before implementation
  - `springdoc-openapi` adds ~2MB to the artifact
- **Mitigation:** API test agents (`@tester-api`) must validate that responses conform to the OpenAPI spec before marking stories as done

> **Amendment, 2026-08-19 (EOP-105): one drift direction is now automated.** The Negative and
> Mitigation above assume drift means *the spec falling behind the server*, caught by a reviewing
> agent. EOP-105 found a third party drifting instead: `ui/src/api.ts` hand-mirrors the contract's
> enums, and it had diverged from both the Java enums and this spec while `docs/api/openapi.yml`
> and the server agreed exactly. Contract-first was not at fault — the mirror of the contract was.
> `EnumMirrorParityTest` now fails `verify` if the Java enum constants, the `enum:` lists in
> `docs/api/openapi.yml` and the `as const` arrays in `ui/src/api.ts` disagree on membership, so for
> mirrored enum members the mitigation is a build gate rather than agent diligence. Everything else
> in this spec — paths, field names, optionality, status codes — is still held only by review. See
> [ADR-009](ADR-009-frontend-react-typescript.md)'s EOP-105 amendment for why the mirror is
> hand-written rather than generated from this file, and
> [ADR-006](ADR-006-build-quality-gates.md) for the gate.

## Related

- [API Design Rules](../../.opencode/rules/api-design.md)
- [OpenAPI Specification](https://spec.openapis.org/oas/v3.1.0)
