import { expect, test } from '@playwright/test';

/**
 * Walking skeleton for the E2E suite (EOP-216).
 *
 * This is deliberately the only test in the story. Its job is to prove the wiring
 * — compose stack, published port, TLS handshake, Caddy routing, static asset
 * delivery and React hydration — before any scenario depends on that wiring being
 * sound. Scenarios arrive in EOP-217, EOP-218 and EOP-219.
 */
test.describe('Smoke: the application serves its home screen', () => {
    test('renders the home screen heading', async ({ page }) => {
        // Arrange: a fresh browser context, so `sessionStorage` is empty and the
        // app has no `eop_session` to restore. That is what makes the home screen
        // the expected landing state rather than a restored lobby.

        // Act
        const response = await page.goto('/');

        // Assert: the navigation itself succeeded.
        expect(response, 'page.goto returned no response').not.toBeNull();
        expect(response!.status()).toBe(200);

        /*
         * Assert the application actually rendered.
         *
         * This single assertion is doing far more work than it looks like, and the
         * reason it is written against rendered text rather than against the
         * status code above is that the status code is close to worthless here.
         * `index.html` is a shell containing an empty `<div id="root">`; every
         * word on the screen is produced by React after the JavaScript bundle
         * loads. So a visible `h1` proves, in order: the TLS handshake completed
         * against Caddy's internal certificate; Caddy matched its site block by
         * hostname despite the request arriving on port 8443 rather than the 8080
         * its site block names; `index.html` was served from `/srv`; the hashed
         * bundle under `/assets/` was served rather than swallowed by the SPA
         * fallback; and the bundle parsed and executed under the Content-Security
         * -Policy the shipped Caddyfile sets.
         *
         * Every one of those has a failure mode that still answers HTTP 200 with a
         * body. The most likely of them — a Host that matches no site block, which
         * returns an empty 200 — is invisible to a status assertion and obvious to
         * this one.
         */
        await expect(page.getByRole('heading', { level: 1, name: 'Threat modelling card game' })).toBeVisible();
    });
});
