import { expect, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';

/**
 * Vocabulary for driving a game through the browser (EOP-217).
 *
 * This module is the seam between the scenarios in `tests/` and the shipped UI's
 * accessible names. It exists for one reason: the same journey is walked by four
 * scenarios across three browser engines, and a locator that drifts should break
 * in one place rather than twelve. It lives beside `stack.ts` rather than under
 * `tests/` so that Playwright's default `testMatch` never mistakes it for a spec.
 *
 * Two properties of the application shape everything here, and both are recorded
 * in ADR-068 because they are surprising:
 *
 *  - **Follow-suit is enforced only by the server.** `GameScreen` disables a card
 *    when it is not your turn and at no other time, so an illegal card is
 *    clickable and comes back as an error. {@link chooseLegalCard} is therefore
 *    not a convenience — without it the scenarios would fail on the rules.
 *  - **A full game is every card in the deck.** `Hands.deal` deals the whole
 *    68-card deck, so a three-player game is 68 plays over 23 tricks and there is
 *    no shorter route to the game-over screen through the UI.
 */

/**
 * Seats the domain requires before a game may start.
 *
 * Mirrors `GameSession.MINIMUM_PLAYERS_TO_START`. The front end hard-codes the
 * same literal at `LobbyScreen.tsx:37` rather than sharing a constant, so this is
 * a third copy — asserted against observed behaviour rather than trusted.
 */
export const MINIMUM_PLAYERS_TO_START = 3;

/**
 * Cards in the printed deck (ADR-041), every one of which is dealt.
 *
 * `Hands.deal` runs to the end of the deck and discards nothing, so this is also
 * the number of card plays in a complete game at any supported seat count.
 */
export const DECK_SIZE = 68;

/** One player: an isolated browser context, its page, and the name it joined under. */
export interface Seat {
    readonly context: BrowserContext;
    readonly page: Page;
    readonly displayName: string;
}

/**
 * The hand sizes `Hands.deal` produces for a given seat count.
 *
 * Round-robin over the whole deck, so hands differ by at most one card and the
 * surplus falls to the lowest seats — 23/23/22 for three players, 17 each for
 * four. Computed rather than hard-coded so that the assertion still describes the
 * dealer if the deck size or the seat count changes.
 *
 * @param playerCount seats in the session
 * @return the expected hand size per seat, in seat order
 */
export function expectedHandSizes(playerCount: number): readonly number[] {
    const base = Math.floor(DECK_SIZE / playerCount);
    const surplus = DECK_SIZE % playerCount;
    return Array.from({ length: playerCount }, (_unused, seat) => base + (seat < surplus ? 1 : 0));
}

/**
 * The suit named by a card's accessible name.
 *
 * A hand card's `aria-label` is `<rank> of <suit>: <threat prompt>` and a played
 * card's `alt` is `<rank> of <suit>` with no prompt, so one parser serves both.
 * The suit arrives lower-cased with underscores replaced by spaces — the same
 * string on both sides, which is what lets a led suit be compared to a hand card
 * without a lookup table. Note the split takes the *first* colon: a threat prompt
 * may legitimately contain others.
 *
 * @param accessibleName the `aria-label` or `alt` text of a card
 * @return the suit, or an empty string if the name is not in that shape
 */
export function suitOf(accessibleName: string): string {
    const beforePrompt = accessibleName.split(':')[0] ?? accessibleName;
    const marker = ' of ';
    const index = beforePrompt.indexOf(marker);
    return index === -1 ? '' : beforePrompt.slice(index + marker.length).trim();
}

/**
 * Index of a card that may legally be played.
 *
 * The rule is follow-suit-if-able and trump grants no exemption: `Trick.play`
 * refuses an off-suit card whenever the hand holds the led suit, whether or not
 * the card is an Elevation of Privilege. So a card of the led suit is chosen when
 * one is held, and otherwise any card is legal.
 *
 * Passing no led suit means leading, where every card is legal. That also makes a
 * stale read harmless: if the trick zone still shows a just-resolved trick while
 * this seat is in fact leading a fresh one, the choice merely *prefers* that suit
 * and remains legal either way.
 *
 * @param cardNames accessible names of the cards in hand, in DOM order
 * @param led the led suit, or `undefined` when this seat leads
 * @return the index of a legal card
 */
export function chooseLegalCard(cardNames: readonly string[], led: string | undefined): number {
    if (led === undefined) {
        return 0;
    }
    const following = cardNames.findIndex(name => suitOf(name) === led);
    return following === -1 ? 0 : following;
}

/** The player's own hand. The single most reliable anchor for the game screen, which has no `h1`. */
export function hand(page: Page): Locator {
    return page.getByRole('group', { name: 'Your hand' });
}

/** The cards currently in the player's hand, in DOM order. Empty once they are played out. */
export function handCards(page: Page): Locator {
    return hand(page).getByRole('button');
}

/**
 * The trick in progress.
 *
 * Addressed by label rather than by role because it is a plain `div` carrying an
 * `aria-label` and no role, so `getByRole` cannot reach it. The em dash in that
 * label is U+2014 and is part of the string.
 */
export function trickZone(page: Page): Locator {
    return page.getByLabel('Current trick — drop a card here to play it');
}

/**
 * The suit led in the current trick, read from the first card played into it.
 *
 * @param page the page to read
 * @return the led suit, or `undefined` when no card has been played yet
 */
export async function ledSuit(page: Page): Promise<string | undefined> {
    const played = trickZone(page).getByRole('img');
    if (await played.count() === 0) {
        return undefined;
    }
    const alt = await played.first().getAttribute('alt');
    return alt === null ? undefined : suitOf(alt);
}

/**
 * Opens an isolated browser context on the home screen.
 *
 * A fresh context is what makes a multi-player test possible at all: contexts
 * share no storage, so each seat gets its own `eop_session` and its own player
 * token. Asserting the home heading here is not ceremony — it is the proof that
 * this context restored nothing.
 *
 * @param browser the browser to open a context in
 * @param displayName the name this seat will join under
 * @return the opened seat
 */
export async function openSeat(browser: Browser, displayName: string): Promise<Seat> {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto('/');
    await expect(
        page.getByRole('heading', { level: 1, name: 'Threat modelling card game' }),
        `${displayName} did not land on the home screen`,
    ).toBeVisible();
    return { context, page, displayName };
}

/** Closes every seat's context. Contexts opened by hand are not torn down by the `browser` fixture. */
export async function closeSeats(seats: readonly Seat[]): Promise<void> {
    await Promise.all(seats.map(seat => seat.context.close()));
}

/**
 * Creates a session as facilitator and returns the join code shown in the lobby.
 *
 * The code is read from the DOM rather than constructed, because it is generated
 * server-side from Crockford base32 (`JoinCode.ALPHABET`) and nothing in the UI
 * or the test can predict it. It is read from the inset text rather than by
 * clicking `Copy code`, which writes to the clipboard — a permission WebKit and
 * Firefox grant differently, and whose failure is swallowed by a `console.error`.
 *
 * @param seat the facilitating seat, on the home screen
 * @return the eight-character join code
 */
export async function createSession(seat: Seat): Promise<string> {
    await seat.page.getByRole('button', { name: 'Create a session' }).click();
    await expect(seat.page.getByRole('heading', { level: 1, name: 'Create a session' })).toBeVisible();
    await seat.page.locator('#display-name').fill(seat.displayName);
    await seat.page.getByRole('button', { name: 'Create a session' }).click();

    await expect(seat.page.getByRole('heading', { level: 1, name: 'Game Lobby' })).toBeVisible();
    const code = await seat.page.locator('.govuk-inset-text strong').first().textContent();
    expect(code, 'the lobby rendered no join code').not.toBeNull();
    const joinCode = (code ?? '').trim();
    expect(joinCode, `join code ${joinCode} is not eight characters`).toHaveLength(8);
    return joinCode;
}

/**
 * Joins an existing session from the home screen.
 *
 * @param seat the joining seat, on the home screen
 * @param joinCode the code shown in the facilitator's lobby
 */
export async function joinSession(seat: Seat, joinCode: string): Promise<void> {
    await seat.page.getByRole('button', { name: 'Join a session' }).click();
    await expect(seat.page.getByRole('heading', { level: 1, name: 'Join a session' })).toBeVisible();
    await seat.page.locator('#join-code').fill(joinCode);
    await seat.page.locator('#display-name').fill(seat.displayName);
    await seat.page.getByRole('button', { name: 'Join a session' }).click();
    await expect(seat.page.getByRole('heading', { level: 1, name: 'Game Lobby' })).toBeVisible();
}

/**
 * Waits until every seat's lobby lists the given number of players.
 *
 * The player count is inside the `h2`'s accessible name — `Players (3)` — which
 * makes it the natural thing to wait on when a join has to propagate to the other
 * contexts over the SSE doorbell. Waiting on this rather than on a timer is the
 * whole point: each context re-fetches when the doorbell rings, and the heading is
 * the first rendered consequence.
 *
 * @param seats the seats to check
 * @param expectedPlayers the number every lobby must show
 */
export async function expectPlayerCount(seats: readonly Seat[], expectedPlayers: number): Promise<void> {
    for (const seat of seats) {
        await expect(
            seat.page.getByRole('heading', { level: 2, name: `Players (${expectedPlayers})` }),
            `${seat.displayName}'s lobby does not list ${expectedPlayers} players`,
        ).toBeVisible();
    }
}

/**
 * Starts the game as facilitator and waits for every seat to be holding cards.
 *
 * One click issues both `POST /start` and `POST /deal`, and the two are not
 * atomic, so a participant whose `game-started` doorbell arrives between them
 * mounts the game screen, is refused a hand with a 409 and renders
 * `Waiting for cards to be dealt...`. That is a legitimate intermediate state:
 * this waits for the hand itself rather than for the screen, so the wait covers
 * both orderings.
 *
 * @param facilitator the seat holding the `Start game` button
 * @param seats every seat in the session
 */
export async function startGame(facilitator: Seat, seats: readonly Seat[]): Promise<void> {
    const start = facilitator.page.getByRole('button', { name: 'Start game' });
    await expect(start, 'Start game is not enabled at the minimum player count').toBeEnabled();
    await start.click();

    for (const seat of seats) {
        await expect(
            handCards(seat.page).first(),
            `${seat.displayName} was never dealt a hand`,
        ).toBeVisible({ timeout: 30_000 });
    }
}

/**
 * Waits for the seat whose turn it is.
 *
 * Turn ownership is not addressable as text: the turn label is rendered twice —
 * once visually hidden inside an `aria-live` region and once `aria-hidden` for
 * sighted users — so matching on `Your turn` resolves two elements and throws.
 * What *is* unambiguous is the cards: `GameScreen` sets `aria-disabled` on every
 * hand card from `!isMyTurn || isPlayingCard`, so exactly one seat has enabled
 * cards at any moment.
 *
 * A seat that has played its last card has no cards at all, which is why the
 * count is checked first — at three players the final trick is played by two
 * seats, the third having run out.
 *
 * @param seats the seats in the session
 * @return the seat entitled to play
 */
export async function waitForSeatToPlay(seats: readonly Seat[]): Promise<Seat> {
    let active: Seat | undefined;
    await expect
        .poll(
            async () => {
                for (const seat of seats) {
                    const cards = handCards(seat.page);
                    if (await cards.count() === 0) {
                        continue;
                    }
                    if (await cards.first().getAttribute('aria-disabled') === 'false') {
                        active = seat;
                        return seat.displayName;
                    }
                }
                active = undefined;
                return null;
            },
            {
                message: 'no seat became entitled to play',
                timeout: 30_000,
                intervals: [100, 250, 500],
            },
        )
        .not.toBeNull();

    if (active === undefined) {
        throw new Error('the poll resolved without recording a seat');
    }
    return active;
}

/**
 * Plays one legal card from a seat and waits for the server to confirm it.
 *
 * Selection then submission, deliberately, rather than the drag-and-drop path the
 * UI also offers: dragging hit-tests pointer coordinates against the drop zone's
 * bounding box, and the trick-won banner mounts and unmounts above the table
 * between tricks, so the geometry moves under the test. `setPointerCapture` and
 * `-webkit-user-drag` are also exactly where the three engines diverge.
 *
 * The success signal is the hand shrinking, not the submit button disappearing:
 * the button is gated on a selection that is cleared *before* the request is
 * sent, so it vanishes whether the play succeeded or failed. Nothing is rendered
 * optimistically, so a card leaves the hand only once the server has accepted it.
 *
 * @param seat the seat entitled to play
 * @return the accessible name of the card played
 */
export async function playLegalCard(seat: Seat): Promise<string> {
    const cards = handCards(seat.page);
    const heldBefore = await cards.count();
    expect(heldBefore, `${seat.displayName} was asked to play with an empty hand`).toBeGreaterThan(0);

    const cardNames = await cards.evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-label') ?? ''));
    const led = await ledSuit(seat.page);
    const index = chooseLegalCard(cardNames, led);
    const chosen = cardNames[index] ?? '';

    const card = cards.nth(index);
    await card.click();
    await expect(card, `${seat.displayName} clicked ${chosen} but it did not become selected`).toHaveAttribute(
        'aria-pressed',
        'true',
    );
    await seat.page.getByRole('button', { name: 'Play selected card' }).click();

    try {
        await expect(cards).toHaveCount(heldBefore - 1);
    } catch (failure) {
        // The hand not shrinking means the server refused the play. Its reason is
        // in the error summary, and surfacing it turns an opaque count mismatch
        // into the actual rule that was broken.
        const problem = await seat.page
            .locator('.govuk-error-summary')
            .first()
            .textContent()
            .catch(() => null);
        throw new Error(
            `${seat.displayName} could not play "${chosen}" (led suit: ${led ?? 'none, leading'}). ` +
                `Error summary: ${problem?.replace(/\s+/g, ' ').trim() ?? 'none rendered'}`,
            { cause: failure },
        );
    }
    return chosen;
}

/**
 * Plays every remaining card in the game.
 *
 * There is nothing to click between tricks: the server resolves a trick inline on
 * the play that completes it, publishes `trick-resolved` and then
 * `game-completed`, and the winner simply leads the next trick. `Start next
 * trick` only dismisses a banner.
 *
 * @param seats the seats in the session
 * @param plays the number of cards left to play
 */
export async function playOutTheDeck(seats: readonly Seat[], plays: number): Promise<void> {
    for (let played = 0; played < plays; played += 1) {
        const seat = await waitForSeatToPlay(seats);
        await playLegalCard(seat);
    }
}

/**
 * Asserts a seat has reached the terminal screen with its final leaderboard.
 *
 * The heading is asserted first because it renders unconditionally, while the
 * leaderboard depends on a second request that may legitimately 404: the game
 * result is persisted asynchronously, so the leaderboard can be asked for before
 * the write lands. The screen offers a retry for exactly this case, so the retry
 * is used rather than a sleep.
 *
 * @param seat the seat to check
 */
export async function expectGameOver(seat: Seat): Promise<void> {
    await expect(
        seat.page.getByRole('heading', { level: 1, name: 'Game over' }),
        `${seat.displayName} did not reach the game-over screen`,
    ).toBeVisible({ timeout: 30_000 });

    const leaderboard = seat.page.getByRole('table', { name: 'Final leaderboard' });
    try {
        await expect(leaderboard).toBeVisible();
    } catch (failure) {
        const retry = seat.page.getByRole('button', { name: 'Retry loading results' });
        if (!(await retry.isVisible())) {
            throw failure;
        }
        await retry.click();
        await expect(leaderboard, `${seat.displayName}'s leaderboard never loaded`).toBeVisible({ timeout: 20_000 });
    }
}
