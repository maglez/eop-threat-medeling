import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameScreen } from './GameScreen';
import { LENS_DIAMETER_PX, LENS_ZOOM } from './CardMagnifier';
import * as api from '../api';

/**
 * EOP-145 — the card magnifier lens.
 *
 * These tests drive the lens through GameScreen rather than through the hook in
 * isolation, because the wiring is the part that can break: which pointer events
 * each surface subscribes to, whether a drag suppresses the lens, and whether a
 * disabled card still magnifies. A hook test would pass with all of that wrong.
 *
 * The flag is stubbed explicitly in every test. A developer's .env.local may set
 * VITE_CARD_MAGNIFIER_ENABLED either way, and an unstubbed test would silently
 * assert whatever position that file happens to hold.
 */
describe('CardMagnifier', () => {
  const mockSession: api.SessionStateDto = {
    sessionId: 'test-session',
    joinCode: 'ABC12345',
    status: 'IN_PROGRESS',
    players: [
      { playerId: 'player1', displayName: 'Alice', seatOrder: 0, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      { playerId: 'player2', displayName: 'Bob', seatOrder: 1, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
      { playerId: 'player3', displayName: 'Charlie', seatOrder: 2, role: 'PARTICIPANT', connectionStatus: 'CONNECTED' },
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z',
  };

  const defaultProps = {
    sessionId: 'test-session',
    playerId: 'player1',
    playerToken: 'test-token',
    session: mockSession,
    onSessionEnd: vi.fn(),
  };

  const spoofingKing: api.CardDto = {
    cardId: 'card1',
    suit: 'SPOOFING',
    rank: 'KING',
    rankSymbol: 'K',
    rankValue: 13,
    threatPrompt: 'An attacker impersonates a user',
  };

  const tamperingQueen: api.CardDto = {
    cardId: 'card2',
    suit: 'TAMPERING',
    rank: 'QUEEN',
    rankSymbol: 'Q',
    rankValue: 12,
    threatPrompt: 'An attacker modifies data in transit',
  };

  const idleTrickState: api.TrickStateDto = {
    complete: false,
    handComplete: false,
    seatToPlay: 0,
  };

  /** Returns the lens element, or null when no lens is rendered. */
  const lens = (): HTMLElement | null => document.querySelector('.eop-card-lens');

  const stubApi = (hand: api.CardDto[], trickState: api.TrickStateDto = idleTrickState): void => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue({
      handId: 'hand1',
      playerId: 'player1',
      cardCount: hand.length,
      cards: hand,
    });
    vi.spyOn(api, 'getTrickState').mockResolvedValue(trickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);
  };

  /** Resolves once the hand has rendered, so the card is present to hover. */
  const waitForHand = async (): Promise<HTMLElement> => {
    await waitFor(() => {
      expect(screen.getByRole('group', { name: 'Your hand' })).toBeInTheDocument();
    });
    const [card] = screen.getAllByRole('button', { name: /of spoofing/ });
    if (card === undefined) throw new Error('the hand rendered no spoofing card to hover');
    return card;
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  describe('when the flag is on', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_CARD_MAGNIFIER_ENABLED', 'true');
      vi.stubEnv('VITE_GAME_SCREEN_ENABLED', 'true');
    });

    it('shows a lens when the mouse moves over a hand card', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      expect(lens()).toBeNull();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      const shown = lens();
      expect(shown).not.toBeNull();
      // jsdom's getBoundingClientRect is all zeroes, so the point is the raw
      // client coordinate and the lens is centred on it.
      expect(shown?.style.left).toBe(`${String(40 - LENS_DIAMETER_PX / 2)}px`);
      expect(shown?.style.top).toBe(`${String(60 - LENS_DIAMETER_PX / 2)}px`);
      expect(shown?.style.width).toBe(`${String(LENS_DIAMETER_PX)}px`);
    });

    it('removes the lens when the pointer leaves the card', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      fireEvent.pointerLeave(card);

      expect(lens()).toBeNull();
    });

    it('magnifies the pixels beneath it at the agreed zoom factor', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      const replica = lens()?.firstElementChild as HTMLElement | undefined;
      expect(replica?.style.transform).toBe(`scale(${String(LENS_ZOOM)})`);
      expect(replica?.style.transformOrigin).toBe('0 0');
    });

    it('keeps the lens out of the accessibility tree and out of the tab order', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      const shown = lens();
      expect(shown?.getAttribute('aria-hidden')).toBe('true');
      expect(shown?.style.pointerEvents).toBe('none');
      // Nothing inside the lens may be reachable by keyboard: the lens is a copy
      // of a card that is already in the tab order, so a focusable duplicate
      // would announce every card twice.
      expect(shown?.querySelectorAll('[tabindex], button, a, input')).toHaveLength(0);
    });

    it('magnifies a card the player may not play yet', async () => {
      // Seat 1 is Bob's, so Alice's cards are disabled — but she must still be
      // able to read them while she waits (EOP-145 scenario 6).
      stubApi([spoofingKing], { complete: false, handComplete: false, seatToPlay: 1 });
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      expect(card.getAttribute('aria-disabled')).toBe('true');

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      expect(lens()).not.toBeNull();
    });

    it('shows a lens over a card already played into the trick', async () => {
      const trickState: api.TrickStateDto = {
        complete: false,
        handComplete: false,
        seatToPlay: 1,
        trick: {
          trickId: 'trick1',
          sequence: 1,
          leaderSeat: 1,
          ledSuit: 'TAMPERING',
          plays: [{
            trickPlayId: 'play1',
            playerId: 'player2',
            seatOrder: 1,
            card: tamperingQueen,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }],
        },
      };
      stubApi([spoofingKing], trickState);
      render(<GameScreen {...defaultProps} />);

      const played = await waitFor(() => screen.getByAltText('Q of tampering'));
      const trickCard = played.parentElement;
      expect(trickCard).not.toBeNull();

      fireEvent.pointerMove(trickCard as HTMLElement, { pointerType: 'mouse', clientX: 20, clientY: 30 });

      expect(lens()).not.toBeNull();
    });

    it('pins the lens on a touch tap and releases it on a second tap', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      // A pinned lens survives the pointer leaving — a finger lifts off, it does
      // not hover away.
      fireEvent.pointerLeave(card);
      expect(lens()).not.toBeNull();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).toBeNull();
    });

    it('releases a pinned lens when the player taps somewhere else', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      fireEvent.pointerDown(document.body, { pointerType: 'touch', clientX: 400, clientY: 400 });

      expect(lens()).toBeNull();
    });

    it('still selects the card when a touch tap magnifies it', async () => {
      // Tap-to-select is the WCAG 2.2 SC 2.5.7 single-pointer alternative to
      // dragging. The magnifier must not consume the tap.
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      fireEvent.click(card);

      expect(card.getAttribute('aria-pressed')).toBe('true');
      expect(screen.getByRole('button', { name: 'Play selected card' })).toBeInTheDocument();
    });

    it('hides the lens when a mouse drag begins', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });
      expect(lens()).not.toBeNull();

      // setPointerCapture would otherwise route every subsequent pointermove to
      // this card, dragging the lens across the table with the ghost.
      fireEvent.pointerDown(card, { pointerType: 'mouse', clientX: 40, clientY: 60, pointerId: 1 });

      expect(lens()).toBeNull();
    });
  });

  describe('when the flag is off', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_CARD_MAGNIFIER_ENABLED', 'false');
      vi.stubEnv('VITE_GAME_SCREEN_ENABLED', 'true');
    });

    it('never shows a lens on hover', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      expect(lens()).toBeNull();
    });

    it('never shows a lens on a touch tap, and still selects the card', async () => {
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerDown(card, { pointerType: 'touch', clientX: 40, clientY: 60 });
      fireEvent.click(card);

      expect(lens()).toBeNull();
      expect(card.getAttribute('aria-pressed')).toBe('true');
    });
  });

  describe('when the flag is unset', () => {
    it('fails closed', async () => {
      // ADR-037: the `=== 'true'` comparison means an absent variable disables
      // the feature rather than enabling it.
      vi.stubEnv('VITE_CARD_MAGNIFIER_ENABLED', '');
      vi.stubEnv('VITE_GAME_SCREEN_ENABLED', 'true');
      stubApi([spoofingKing]);
      render(<GameScreen {...defaultProps} />);
      const card = await waitForHand();

      fireEvent.pointerMove(card, { pointerType: 'mouse', clientX: 40, clientY: 60 });

      expect(lens()).toBeNull();
    });
  });
});
