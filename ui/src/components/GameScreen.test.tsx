import { render, screen, waitFor, fireEvent, act, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameScreen } from './GameScreen';
import * as api from '../api';

// Mock the ErrorSummary component
vi.mock('./ErrorSummary', () => ({
  ErrorSummary: ({ title, errors }: { title: string; errors: string[] }) => (
    <div data-testid="error-summary">
      <h2>{title}</h2>
      <ul>
        {errors.map((error, index) => (
          <li key={index}>{error}</li>
        ))}
      </ul>
    </div>
  )
}));

describe('GameScreen', () => {
  const mockSession: api.SessionStateDto = {
    sessionId: 'test-session',
    joinCode: 'ABC123',
    status: 'IN_PROGRESS',
    players: [
      { playerId: 'player1', displayName: 'Alice', seatOrder: 0, role: 'PLAYER', connectionStatus: 'CONNECTED' },
      { playerId: 'player2', displayName: 'Bob', seatOrder: 1, role: 'PLAYER', connectionStatus: 'CONNECTED' },
      { playerId: 'player3', displayName: 'Charlie', seatOrder: 2, role: 'PLAYER', connectionStatus: 'CONNECTED' }
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z'
  };

  const defaultProps = {
    sessionId: 'test-session',
    playerId: 'player1',
    playerToken: 'test-token',
    session: mockSession,
    onSessionEnd: vi.fn()
  };

  const makeHand = (cards: api.CardDto[]): api.HandDto => ({
    handId: 'hand1',
    playerId: 'player1',
    cardCount: cards.length,
    cards,
  });

  const spoofingAce: api.CardDto = {
    cardId: 'card1',
    suit: 'SPOOFING',
    rank: 'ACE',
    rankSymbol: 'A',
    rankValue: 14,
    threatPrompt: 'Test prompt 1',
  };

  const tamperingKing: api.CardDto = {
    cardId: 'card2',
    suit: 'TAMPERING',
    rank: 'KING',
    rankSymbol: 'K',
    rankValue: 13,
    threatPrompt: 'Test prompt 2',
  };

  const repudiationQueen: api.CardDto = {
    cardId: 'card3',
    suit: 'REPUDIATION',
    rank: 'QUEEN',
    rankSymbol: 'Q',
    rankValue: 12,
    threatPrompt: 'Test prompt 3',
  };

  const idleTrickState: api.TrickStateDto = {
    complete: false,
    handComplete: false,
    seatToPlay: 0,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders loading state before data arrives', () => {
    vi.spyOn(api, 'fetchHand').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'getTrickState').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'getSession').mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    expect(screen.getByText('Waiting for cards to be dealt...')).toBeInTheDocument();
  });

  it("renders the player's hand with rank symbols after data loads", async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce, tamperingKing, repudiationQueen]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    // Wait for hand to load — rank symbols are rendered
    await waitFor(() => {
      // Each card renders its rankSymbol twice (top-left and bottom-right corners)
      expect(screen.getAllByText('A').length).toBeGreaterThanOrEqual(1);
    });

    expect(screen.getAllByText('K').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Q').length).toBeGreaterThanOrEqual(1);
  });

  it("renders the player's display name above their hand", async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByText('Alice')).toBeInTheDocument();
    });
  });

  it('disables cards when it is not the player\'s turn', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue({
      complete: false,
      handComplete: false,
      seatToPlay: 1, // Bob's turn, not Alice's
    });
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // Card is rendered as a role="button" with aria-disabled
      // aria-label format: "{rankSymbol} of {suit}: {threatPrompt}"
      const cardButtons = screen.getAllByRole('button');
      const cardButton = cardButtons.find(btn => btn.getAttribute('aria-label')?.includes('of spoofing'));
      expect(cardButton).toBeDefined();
      expect(cardButton).toHaveAttribute('aria-disabled', 'true');
    });
  });

  it('shows whose turn it is', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue({
      complete: false,
      handComplete: false,
      seatToPlay: 1, // Bob's turn
    });
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // Both the aria-live region and the visible paragraph show the turn label
      expect(screen.getAllByText("Bob's turn").length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows 'Your turn' when it is the current player's turn", async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState); // seatToPlay: 0 = Alice
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getAllByText('Your turn').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('allows card selection and shows play button when it is the player\'s turn', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getAllByText('A').length).toBeGreaterThanOrEqual(1);
    });

    // Click the card — aria-label format: "{rankSymbol} of {suit}: {threatPrompt}"
    const cardButton = screen.getByRole('button', { name: /A of spoofing/i });
    fireEvent.click(cardButton);

    // Play selected card button should appear
    expect(screen.getByRole('button', { name: 'Play selected card' })).toBeInTheDocument();
  });

  it('marks card as selected (aria-pressed) after click', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /A of spoofing/i })).toBeInTheDocument();
    });

    const cardButton = screen.getByRole('button', { name: /A of spoofing/i });
    expect(cardButton).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(cardButton);
    expect(cardButton).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows trick winner banner when trick is complete', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingAce,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0,
      },
    };

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    const alertBanner = screen.getByRole('alert');
    expect(within(alertBanner).getByText('Trick won!')).toBeInTheDocument();
    // Winner's name appears in the banner
    expect(within(alertBanner).getByText(/Alice/)).toBeInTheDocument();
  });

  it('shows "Start next trick" button only for the trick winner', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingAce,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0, // Alice wins
      },
    };

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    // Alice is the current player and the winner — "Start next trick" should appear
    expect(screen.getByRole('button', { name: 'Start next trick' })).toBeInTheDocument();
  });

  it('auto-dismisses winner banner after 5 seconds', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingAce,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0,
      },
    };

    // Use fake timers from the start but keep Promise/microtask scheduling real
    vi.useFakeTimers({ shouldAdvanceTime: false, toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    // Flush all pending promises so the component finishes loading and sets the timer
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    // Banner should now be visible
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // Advance fake timers past the 5s auto-dismiss
    act(() => {
      vi.advanceTimersByTime(5001);
    });

    // Banner should be gone
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    vi.useRealTimers();
  });

  it('dismisses winner banner when Dismiss button is clicked', async () => {
    const completeTrickState: api.TrickStateDto = {
      complete: true,
      handComplete: false,
      trick: {
        trickId: 'trick1',
        sequence: 1,
        leaderSeat: 0,
        plays: [
          {
            trickPlayId: 'play1',
            playerId: 'player1',
            seatOrder: 0,
            card: spoofingAce,
            threatLinked: false,
            components: [],
            playedAt: '2023-01-01T00:00:00Z',
          }
        ],
        winningSeat: 0,
      },
    };

    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(completeTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('handles API errors gracefully and shows error summary', async () => {
    vi.spyOn(api, 'fetchHand').mockRejectedValue(new Error('Failed to fetch hand'));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByTestId('error-summary')).toBeInTheDocument();
    });

    expect(screen.getByText('Failed to fetch hand')).toBeInTheDocument();
  });

  it('shows waiting state (not session end) when /hand returns 409 (cards not yet dealt)', async () => {
    const notDealtError = new api.ApiError(409, 'Hands not dealt');
    vi.spyOn(api, 'fetchHand').mockRejectedValue(notDealtError);
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    const onSessionEnd = vi.fn();

    render(<GameScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

    await waitFor(() => {
      expect(screen.getByText('Waiting for cards to be dealt...')).toBeInTheDocument();
    });

    // Session must NOT have ended — the player is still in the game
    expect(onSessionEnd).not.toHaveBeenCalled();
    // No error summary shown — this is a normal waiting state
    expect(screen.queryByTestId('error-summary')).not.toBeInTheDocument();
  });

  it('calls onSessionEnd when getSession returns 404 (session genuinely gone)', async () => {
    const sessionGoneError = new api.ApiError(404, 'Session not found');
    vi.spyOn(api, 'getSession').mockRejectedValue(sessionGoneError);
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    const onSessionEnd = vi.fn();

    render(<GameScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

    await waitFor(() => {
      expect(onSessionEnd).toHaveBeenCalledTimes(1);
    });
  });

  it('shows other players around the table', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // Bob and Charlie are other players — their names should appear
      expect(screen.getByText('Bob')).toBeInTheDocument();
      expect(screen.getByText('Charlie')).toBeInTheDocument();
    });
  });

  it('shows the central trick zone drop area', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByLabelText(/current trick.*drop a card/i)).toBeInTheDocument();
    });
  });

  it('shows "No cards remaining" when hand is empty', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByText('No cards remaining')).toBeInTheDocument();
    });
  });

  it('card buttons carry className eop-card so the CSS focus ring is reachable', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      const card = screen.getByRole('button', { name: /A of spoofing/i });
      expect(card).toHaveClass('eop-card');
    });
  });

  it('applies GOV.UK focus box-shadow (yellow + dark inset) when card receives focus', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingAce]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    const card = await screen.findByRole('button', { name: /A of spoofing/i });

    // Before focus: no GOV.UK focus shadow
    expect(card).not.toHaveStyle({ boxShadow: '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c' });

    // Trigger focus
    fireEvent.focus(card);
    expect(card).toHaveStyle({ boxShadow: '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c' });

    // Trigger blur — focus shadow removed
    fireEvent.blur(card);
    expect(card).not.toHaveStyle({ boxShadow: '0 0 0 3px #ffdd00, inset 0 0 0 2px #0b0c0c' });
  });
});

// ---- Card images feature flag (EOP-66) ----
// These tests exercise the flag-ON path. VITE_CARD_IMAGES_ENABLED is read at
// component scope in CardFace and GameScreen, so vi.stubEnv takes effect on
// the next render without needing vi.resetModules().

describe('GameScreen — card images (VITE_CARD_IMAGES_ENABLED=true)', () => {
  const mockSession: api.SessionStateDto = {
    sessionId: 'test-session',
    joinCode: 'ABC123',
    status: 'IN_PROGRESS',
    players: [
      { playerId: 'player1', displayName: 'Alice', seatOrder: 0, role: 'PLAYER', connectionStatus: 'CONNECTED' },
      { playerId: 'player2', displayName: 'Bob', seatOrder: 1, role: 'PLAYER', connectionStatus: 'CONNECTED' },
      { playerId: 'player3', displayName: 'Charlie', seatOrder: 2, role: 'PLAYER', connectionStatus: 'CONNECTED' },
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z',
  };

  // A card with a real playable rank so cardImagePath returns a non-null URL.
  const spoofingKing: api.CardDto = {
    cardId: 'card-sk',
    suit: 'SPOOFING',
    rank: 'KING',
    rankSymbol: 'K',
    rankValue: 13,
    threatPrompt: 'Spoofing king threat',
  };

  const makeHand = (cards: api.CardDto[]): api.HandDto => ({
    handId: 'hand1',
    playerId: 'player1',
    cardCount: cards.length,
    cards,
  });

  const idleTrickState: api.TrickStateDto = {
    complete: false,
    handComplete: false,
    seatToPlay: 0,
  };

  const defaultProps = {
    sessionId: 'test-session',
    playerId: 'player1',
    playerToken: 'test-token',
    session: mockSession,
    onSessionEnd: vi.fn(),
  };

  beforeEach(() => {
    vi.stubEnv('VITE_CARD_IMAGES_ENABLED', 'true');
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('renders an <img> for a card with a real image when flag is ON', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // The card image should be rendered with a descriptive alt attribute
      const img = screen.getByAltText('K of spoofing');
      expect(img).toBeInTheDocument();
      expect(img.tagName).toBe('IMG');
    });
  });

  it('img alt attribute is descriptive (rank + suit, lowercase)', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      const img = screen.getByAltText('K of spoofing');
      expect(img).toHaveAttribute('alt', 'K of spoofing');
    });
  });

  it('does not render rank symbol spans when card image is shown', async () => {
    vi.spyOn(api, 'fetchHand').mockResolvedValue(makeHand([spoofingKing]));
    vi.spyOn(api, 'getTrickState').mockResolvedValue(idleTrickState);
    vi.spyOn(api, 'getSession').mockResolvedValue(mockSession);
    vi.spyOn(api, 'subscribeToSession').mockReturnValue({ abort: vi.fn() } as unknown as AbortController);

    render(<GameScreen {...defaultProps} />);

    await waitFor(() => {
      // The <img> is present
      expect(screen.getByAltText('K of spoofing')).toBeInTheDocument();
    });

    // The plain-text rank symbol fallback should NOT be present when the image renders
    // (the ternary renders either <img> or the text spans, never both)
    const rankSymbolSpans = screen.queryAllByText('K');
    // All 'K' text nodes should be absent from the card face (image replaced them)
    // Note: there may be zero or the aria-label still contains 'K', but no visible span
    expect(rankSymbolSpans.length).toBe(0);
  });
});
