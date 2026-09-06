import { execFileSync } from 'node:child_process';
import * as path from 'node:path';

/**
 * Shared helpers for driving the `compose.e2e.yml` stack.
 *
 * Both `global-setup.ts` and `global-teardown.ts` import from here so that the
 * compose file path, the base URL and the two escape-hatch environment variables
 * are defined exactly once. Two copies of that logic would drift, and a teardown
 * that disagreed with its setup about which stack it owns is the one bug in this
 * file that would be expensive: it would either delete a developer's own running
 * stack or leave a test stack behind holding port 8443.
 */

/**
 * Repository root, resolved from this file rather than from `process.cwd()`.
 *
 * Playwright sets the working directory to the config's directory (`e2e/`), but
 * that is a Playwright behaviour rather than a guarantee, and `npm test` from the
 * repository root would give a different answer. `compose.e2e.yml` lives at the
 * root because it composes the root `Dockerfile`'s image alongside `ui/`'s, so
 * every compose invocation has to run from there.
 */
export const REPO_ROOT = path.resolve(__dirname, '..');

/** Compose file for the E2E stack, relative to {@link REPO_ROOT}. */
export const COMPOSE_FILE = 'compose.e2e.yml';

/**
 * Where the tests point.
 *
 * `https`, not `http`: the `eop-ui` image bakes its own Caddyfile in at build
 * time and that Caddyfile serves TLS only, with `auto_https disable_redirects`,
 * so nothing listens on plain HTTP. The host must be spelled `localhost` and not
 * `127.0.0.1` — Caddy's internal certificate is issued for the names in its site
 * block and it aborts the handshake on a non-matching SNI, which no client-side
 * flag can rescue. Port 8443 rather than 443 keeps this stack clear of
 * `compose.app.yml`. See ADR-068.
 */
export const BASE_URL = process.env.E2E_BASE_URL ?? 'https://localhost:8443';

/**
 * True when an environment variable is set to an affirmative value.
 *
 * Deliberately strict, and deliberately fail-closed for the callers below: an
 * unset, empty or unrecognised value means "no", so a typo leaves the default
 * behaviour (manage the stack, tear it down) rather than silently disabling it.
 * `E2E_KEEP_STACK=false` reads as false, which is the reading a developer who
 * writes it expects; a looser check would treat it as true.
 */
function isEnabled(name: string): boolean {
    const raw = process.env[name];
    if (raw === undefined) {
        return false;
    }
    return ['1', 'true', 'yes', 'on'].includes(raw.trim().toLowerCase());
}

/**
 * True when the caller has already started the stack and this run must not touch
 * its lifecycle.
 *
 * CI starts the stack itself so that it can capture container logs and publish
 * them as an artefact when a test fails — something a `globalTeardown` that has
 * already run `down -v` cannot do. A developer iterating on one spec also wants
 * this: booting Postgres, Spring Boot and Caddy on every `playwright test` costs
 * far more than the test.
 */
export function usesExternalStack(): boolean {
    return isEnabled('E2E_REUSE_STACK');
}

/**
 * True when the stack should be left running after the tests finish.
 *
 * For inspecting a failure by hand — the database still holds the session the
 * test created, and `docker logs eop-e2e-app` still has the stack trace. Note
 * this leaves the volumes in place too, so the next run without it starts from a
 * *dirty* database unless `down -v` is run manually. That is the trade, and it is
 * why this is opt-in.
 */
export function keepsStackRunning(): boolean {
    return isEnabled('E2E_KEEP_STACK');
}

/**
 * Run `docker compose -f compose.e2e.yml <args>` from the repository root.
 *
 * `execFileSync` rather than `execSync`: no shell, so nothing here can be
 * confused by a metacharacter arriving through an environment variable.
 * `stdio: 'inherit'` so that compose's own progress and any container startup
 * error reach the terminal — a swallowed pull failure or an unhealthy container
 * is exactly what someone debugging a failed setup needs to see. Throws on a
 * non-zero exit, which in `globalSetup` aborts the run before a single test
 * produces a misleading failure.
 *
 * @param args arguments to pass to `docker compose` after the `-f` flag
 */
export function compose(...args: string[]): void {
    execFileSync('docker', ['compose', '-f', COMPOSE_FILE, ...args], {
        cwd: REPO_ROOT,
        stdio: 'inherit',
    });
}
