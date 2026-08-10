- Use `@ConditionalOnProperty` with `application.yml` toggles — no additional library needed (ADR-013 rejected Unleash, Flipt and FF4J)
- Naming convention: `eop.features.{kebab-case-feature}` holding a plain boolean — the shipped example is `eop.features.session-lifecycle`. No `ff_` prefix, no `_v{n}` suffix, no second root namespace
- Default all flags to `false` (opt-in) — an unset flag reads as disabled, so forgetting to think about one fails closed
- Flag config lives in `src/main/resources/application.yml` under the `eop.features` root:
  ```yaml
  eop:
    features:
      session-lifecycle: false
  ```
- A flag is bound at startup, so flipping it restarts the application. Override it in a deployed container by setting the env var (`EOP_FEATURES_SESSION_LIFECYCLE=true`) in the environment the container starts with, then `docker compose -f compose.app.yml up -d` (ADR-013, ADR-016)
- Flipping a flag anywhere other than your own machine is a reviewed change, not a convenience — ADR-013 accepts that there is no audit trail beyond the shell history of whoever set the variable, so move the flag's default in `application.yml` under review rather than leaving an environment override in place
- In tests, set the suite-wide position in `src/test/resources/application.properties` (flags are ON there, because a suite running with the feature off would be testing its absence), and override a single scenario with `@SpringBootTest(properties = "eop.features.session-lifecycle=false")`. There is no `application-test.yml` and no `test` profile — see `configuration.md`
- Test the flag in its OFF position too, and assert the bean is absent as well as the routes returning 404 — asserting only on 404s still passes if the bean exists but its handlers are mapped elsewhere (see `SessionControllerDisabledIntegrationTest`)
- Gate experimental or incomplete features behind flags — merge to `main` with flag OFF
- Remove flag and its conditional checks once feature is stable (one release after full rollout). This is the only thing bounding a second consequence of the unversioned name: because the key never changes, an override set months ago for one meaning silently governs whatever that flag comes to mean later, and with no audit trail there is nothing to reveal that the override predates the behaviour it now enables
- The flag namespace is a convention a reviewer enforces, not something the compiler checks. Record each flag in `src/main/resources/application.yml` next to its default and in ADR-013; there is no separate flag catalogue document
