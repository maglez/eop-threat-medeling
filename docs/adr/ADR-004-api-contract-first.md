# ADR-004: API Contract-First with OpenAPI

**Status:** Accepted
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

## Related

- [API Design Rules](../../.opencode/rules/api-design.md)
- [OpenAPI Specification](https://spec.openapis.org/oas/v3.1.0)
