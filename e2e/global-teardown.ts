import { compose, keepsStackRunning, usesExternalStack } from './stack';

/**
 * Stop the E2E stack and destroy its volumes.
 *
 * Runs after every test in every project has finished, whether they passed or
 * failed. Two escape hatches, both opt-in and both logged so that a stack left
 * behind is never a surprise:
 *
 * - `E2E_REUSE_STACK` — this run did not start the stack, so it does not get to
 *   stop it. CI sets this precisely so that it can collect container logs *after*
 *   the tests and before tearing down; a teardown here would delete the evidence.
 * - `E2E_KEEP_STACK` — leave it running for inspection by hand.
 *
 * `-v` is not incidental. The suite requires a database with no prior sessions in
 * it: the leaderboard scenarios in EOP-219 assert on historical games, so a
 * leftover game from an earlier run would make them pass or fail for reasons
 * unrelated to the code under test. Destroying the volumes is what makes "fresh
 * database per run" true rather than aspirational. It also drops Caddy's `/data`,
 * so the internal CA is regenerated next run — harmless for Playwright, which
 * launches profiles with no trust store of their own, and noted in
 * `compose.e2e.yml` for anyone pointing a real browser at port 8443.
 */
async function globalTeardown(): Promise<void> {
    if (usesExternalStack()) {
        console.log('[e2e] E2E_REUSE_STACK is set — leaving the stack alone');
        return;
    }

    if (keepsStackRunning()) {
        console.log(
            '[e2e] E2E_KEEP_STACK is set — leaving the stack running. ' +
                'Tear it down with `docker compose -f compose.e2e.yml down -v`; ' +
                'until you do, the next run starts against a dirty database.',
        );
        return;
    }

    console.log('[e2e] stopping compose.e2e.yml and removing its volumes');
    compose('down', '-v', '--remove-orphans');
}

export default globalTeardown;
