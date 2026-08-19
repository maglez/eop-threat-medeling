- Nothing uses Resilience4j today, and the dependency is not even present: `grep -rn 'resilience4j' pom.xml src/` returns no matches, there is no `@Retry`, `@CircuitBreaker` or `@TimeLimiter` anywhere in `src/`, and no `resilience4j.*` block in either profile file. The rules below describe how resilience must be introduced, not something already in place
- This is a weaker position than `caching.md`'s, and the difference is worth keeping straight: caching at least has `spring-boot-starter-cache` on the classpath (`pom.xml`) waiting to be switched on, whereas resilience has no library at all, so adopting it starts with a dependency — and therefore an ADR, not just an annotation
- The reason it is absent is that there is nothing yet to wrap. Resilience4j guards **calls that leave the process**, and this application makes none: there is no `RestTemplate`, `WebClient`, `RestClient`, `HttpClient` or Feign client in `src/main/java/`. Its only outbound dependency is the database, reached through Spring Data. Adding the library before the first external call would ship an unused dependency — supply-chain surface for zero benefit
- Do not mistake `SessionResilienceIntegrationTest` for coverage of any of this. It exercises in-process session robustness — seats surviving the loss of every SSE subscriber, and concurrent joins — and imports nothing from Resilience4j. The word is shared; the mechanism is not
- `CHANGELOG.md` already lists these patterns under "Decided, not yet implemented", so that section and this file must move together: if Resilience4j is adopted, delete the entry there and the disclosure here in the same change

Once a genuine external call exists, introduce it as follows.

- Use Resilience4j for all resilience patterns (wraps external service calls)
- **Retry**: auto-retry transient failures (network timeouts, 5xx) with exponential backoff — max 3 attempts
- **Circuit Breaker**: open after 50% failure rate in sliding window (min 5 calls) — half-open after 10s
- **Time Limiter**: timeout external calls at 5s by default, configurable per service
- Annotate service methods with `@Retry`, `@CircuitBreaker`, `@TimeLimiter` — never wrap in try-catch at call site
- Log circuit state transitions at `WARN` level for operational awareness
- Keep config in `application.yml` under the `resilience4j.*` namespace, with any environment-specific override in `application-prod.yml`. Those are the only two profiles that exist — there is no `dev` and no `test` profile to externalise into, so do not describe this config as "per profile" as though there were a profile per environment (see `configuration.md` and ADR-012)
