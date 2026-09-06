import { defineConfig, devices } from '@playwright/test';
import { BASE_URL } from './stack';

/**
 * Playwright configuration for the end-to-end UI suite (ADR-068).
 *
 * The suite drives the real, shipped artefacts: the `eop-threat-modeling` API
 * image and the `eop-ui` image, composed by `compose.e2e.yml` exactly as
 * `compose.app.yml` composes them for a deployment. Nothing here stubs the
 * back end and nothing here builds the front end — if the assertion passes, it
 * passed against the bundle that ships.
 */
export default defineConfig({
    testDir: './tests',

    // Fail the run if a `test.only` was committed. Under CI only: leaving it
    // focused locally is how anyone iterates on one scenario.
    forbidOnly: !!process.env.CI,

    /*
     * Serial, deliberately, and this is a starting position rather than a
     * conclusion.
     *
     * Every test in every project talks to ONE application instance backed by ONE
     * database. Most scenarios are naturally independent — each creates its own
     * session and drives its own seats — but two kinds of shared state are not:
     * the leaderboard reads the whole history of games (ADR-030), and both rate
     * limiters key on the client address, which resolves to the same host address
     * for every browser this config launches. Parallel workers would make the
     * first non-deterministic and would divide the second between them.
     *
     * The cost is honest: runtime is the sum of the scenarios times three
     * browsers. Revisit it when the suite is large enough for that to hurt, with
     * a measurement rather than a hunch, and relax it per-file with
     * `test.describe.configure({ mode: 'parallel' })` on the files that are
     * provably independent instead of flipping it globally.
     */
    fullyParallel: false,
    workers: 1,

    /*
     * One retry in CI, none locally.
     *
     * A retried test that passes is reported as *flaky*, not as passed, so this
     * hides nothing: the HTML report published for EOP-221 names it, and a flaky
     * result is a defect to investigate rather than a result to accept. The retry
     * exists because the shared GitHub runner is slow and contended enough that a
     * genuine network or container hiccup would otherwise fail a merge for
     * reasons unrelated to the change. Locally, zero — a flake you can reproduce
     * is a flake worth reading.
     */
    retries: process.env.CI ? 1 : 0,

    /*
     * 60s per test rather than the 30s default. A lifecycle scenario creates a
     * session, joins a second player in a second browser context, starts the game
     * and plays tricks, and each step waits on an SSE doorbell followed by a
     * re-fetch (ADR-014). Individual `expect` calls get 10s for the same reason:
     * the default 5s is tuned for a local dev server, not for a proxied round
     * trip to a containerised API.
     */
    timeout: 60_000,
    expect: {
        timeout: 10_000,
    },

    globalSetup: './global-setup.ts',
    globalTeardown: './global-teardown.ts',

    /*
     * `list` for a readable terminal, `html` as the stakeholder artefact EOP-215
     * asks for, `json` as a machine-readable summary for the report index page
     * EOP-221 builds. `open: 'never'` because a report that launches a browser
     * would hang a CI step.
     */
    reporter: [
        ['list'],
        ['html', { open: 'never', outputFolder: 'playwright-report' }],
        ['json', { outputFile: 'results.json' }],
    ],

    use: {
        baseURL: BASE_URL,

        /*
         * The stack serves TLS with Caddy's internal CA, which no browser trusts
         * and which is regenerated whenever the volumes are dropped. Trusting it
         * properly would mean extracting the root out of the container and
         * installing it into three browsers' stores on every developer machine
         * and every CI runner, to gain nothing: the certificate is not what these
         * tests are about.
         *
         * This is the same trade the rest of the repository already makes against
         * the same stack — the CI smoke test uses `curl -fsSk`, the k6 canary uses
         * `--insecure-skip-tls-verify`, and the UI image's own HEALTHCHECK uses
         * `wget --no-check-certificate`. It is a test-only setting in a test-only
         * package; nothing here ships.
         */
        ignoreHTTPSErrors: true,

        // Diagnostics on failure only, so a green run leaves no large artefacts
        // behind. `on-first-retry` pairs with `retries` above: the first attempt
        // runs untraced at full speed and the retry captures everything.
        trace: 'on-first-retry',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure',
    },

    /*
     * All three engines, per EOP-215. WebKit is the one that earns its place: it
     * is the only proxy available for Safari, and Safari's `EventSource` and
     * `sessionStorage` behaviour is where a single-page app that leans on both
     * (ADR-014, and the `eop_session` restore in App.tsx) is most likely to
     * diverge. Firefox and Chromium are cheap by comparison.
     */
    projects: [
        {
            name: 'chromium',
            use: { ...devices['Desktop Chrome'] },
        },
        {
            name: 'firefox',
            use: { ...devices['Desktop Firefox'] },
        },
        {
            name: 'webkit',
            use: { ...devices['Desktop Safari'] },
        },
    ],
});
