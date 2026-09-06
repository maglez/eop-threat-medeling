import { request } from '@playwright/test';
import { BASE_URL, compose, usesExternalStack } from './stack';

/**
 * How long to wait for the stack to answer on the published port.
 *
 * Generous because this covers a cold start: Postgres initialising its data
 * directory into a fresh volume, then Liquibase running every changelog before
 * Spring Boot binds a port. `docker compose up --wait` has usually already
 * absorbed that by the time this poll starts, so on a warm machine the first
 * attempt succeeds.
 */
const HEALTH_TIMEOUT_MS = 120_000;

/** Gap between health attempts. */
const HEALTH_INTERVAL_MS = 1_000;

/**
 * Sleep, without pulling in a dependency for it.
 *
 * @param ms milliseconds to wait
 * @return a promise resolving once the delay has elapsed
 */
function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Wait until `GET /health` answers `200` with a body of exactly `OK`.
 *
 * This runs from the **host**, through the published port and through Caddy's
 * TLS, and that is the whole point of doing it after `up --wait`. Compose's
 * `--wait` proves each container's own healthcheck passes *inside* the container
 * — the app image probes `http://127.0.0.1:8080/health` on its loopback and the
 * UI image probes its own `https://localhost:8080/index.html`. Neither says
 * anything about the port publication, the host firewall, or the TLS handshake
 * that every Playwright browser is about to depend on. A stack that is healthy by
 * compose's reckoning and unreachable from the host is a real failure mode, and
 * it is far cheaper to diagnose here than as three browsers' worth of identical
 * navigation timeouts.
 *
 * The body is checked as well as the status because `/health` is proxied to the
 * application by Caddy, and a Caddy that matched the wrong route — or matched no
 * site block at all — answers `200` with an empty body. Asserting `OK` is what
 * distinguishes "the application is up" from "something answered".
 *
 * @throws Error if the stack does not become reachable within
 *     {@link HEALTH_TIMEOUT_MS}, carrying the last status or transport error seen
 */
async function waitForHealth(): Promise<void> {
    const context = await request.newContext({ ignoreHTTPSErrors: true });
    const deadline = Date.now() + HEALTH_TIMEOUT_MS;
    let lastFailure = 'no attempt completed';

    try {
        while (Date.now() < deadline) {
            try {
                const response = await context.get(`${BASE_URL}/health`);
                const body = (await response.text()).trim();
                if (response.status() === 200 && body === 'OK') {
                    return;
                }
                lastFailure = `HTTP ${response.status()} with body ${JSON.stringify(body)}`;
            } catch (error) {
                lastFailure = error instanceof Error ? error.message : String(error);
            }
            await sleep(HEALTH_INTERVAL_MS);
        }
    } finally {
        await context.dispose();
    }

    throw new Error(
        `The E2E stack did not become reachable at ${BASE_URL}/health within ` +
            `${HEALTH_TIMEOUT_MS / 1000}s. Last attempt: ${lastFailure}. ` +
            'Check `docker compose -f compose.e2e.yml ps` and the container logs. ' +
            'A connection refused here usually means the images are missing or ' +
            'stale — see e2e/README.md for the two `docker build` commands.',
    );
}

/**
 * Start the E2E stack and wait until it serves traffic.
 *
 * Skips the `up` when `E2E_REUSE_STACK` is set, but never skips the health wait:
 * whoever owns the stack, the tests cannot run until it answers, and failing here
 * with one clear message beats failing in every spec.
 */
async function globalSetup(): Promise<void> {
    if (usesExternalStack()) {
        console.log(`[e2e] E2E_REUSE_STACK is set — using the stack already running at ${BASE_URL}`);
    } else {
        console.log('[e2e] starting compose.e2e.yml');
        // --wait blocks until every service with a healthcheck reports healthy and
        // fails if one reports unhealthy, so a broken stack aborts here rather than
        // surfacing as a navigation timeout in each spec. --remove-orphans keeps a
        // renamed or deleted service from a previous revision of the compose file
        // from lingering on the network.
        compose('up', '-d', '--wait', '--remove-orphans');
    }

    console.log(`[e2e] waiting for ${BASE_URL}/health`);
    await waitForHealth();
    console.log('[e2e] stack is serving');
}

export default globalSetup;
