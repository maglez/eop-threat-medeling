import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LobbyScreen } from './LobbyScreen';
import type { SessionStateDto } from '../api';

const mockSession: SessionStateDto = {
  sessionId: 'session-1',
  joinCode: 'ABC123',
  status: 'LOBBY',
  players: [
    {
      playerId: 'player-1',
      displayName: 'Facilitator',
      seatOrder: 0,
      role: 'FACILITATOR',
      connectionStatus: 'CONNECTED'
    },
    {
      playerId: 'player-2',
      displayName: 'Player 2',
      seatOrder: 1,
      role: 'PARTICIPANT',
      connectionStatus: 'CONNECTED'
    },
    {
      playerId: 'player-3',
      displayName: 'Player 3',
      seatOrder: 2,
      role: 'PARTICIPANT',
      connectionStatus: 'CONNECTED'
    }
  ],
  createdAt: '2023-01-01T00:00:00Z',
  updatedAt: '2023-01-01T00:00:00Z'
};

const mockInProgressSession: SessionStateDto = {
  ...mockSession,
  status: 'IN_PROGRESS'
};

describe('LobbyScreen', () => {
  const mockOnSessionEnd = vi.fn();
  const sessionId = 'session-1';
  const playerId = 'player-1';
  const playerToken = 'test-token';

  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders loading state initially', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {}))); // Never resolving promise

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    expect(screen.getByText('Loading session...')).toBeInTheDocument();
  });

  it('renders session details when loaded', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        // SSE stream - never resolve to simulate ongoing connection
        return new Promise(() => {});
      }
      // Regular session fetch
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    // Wait for session to load
    await waitFor(() => {
      expect(screen.getByText('Game Lobby')).toBeInTheDocument();
    });

    expect(screen.getByText('ABC123')).toBeInTheDocument();
    expect(screen.getByText('Facilitator', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByText('Player 2', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByText('Players (3)')).toBeInTheDocument();

    // Check facilitator tag (using specific selector to avoid ambiguity)
    expect(screen.getByText('Facilitator', { selector: 'strong' })).toBeInTheDocument();

    // Check connection status tags
    expect(screen.getAllByText('Connected')).toHaveLength(3);
  });

  it('shows start game button for facilitator when there are enough players', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Start game' })).toBeInTheDocument();
    });
  });

  it('disables start game button when there are fewer than 3 players', async () => {
    const sessionWithTwoPlayers = {
      ...mockSession,
      players: [mockSession.players[0], mockSession.players[1]] // Only facilitator + 1 player
    };

    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(sessionWithTwoPlayers)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      const startButton = screen.getByRole('button', { name: 'Start game' });
      expect(startButton).toBeInTheDocument();
      expect(startButton).toBeDisabled();
    });
  });

  it('does not show start game button for regular players', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Start game' })).not.toBeInTheDocument();
    });
  });

  it('calls onSessionEnd when the session has already reached COMPLETED', async () => {
    // A player sitting in the lobby when the facilitator ends the session receives
    // `game-completed` on the SSE doorbell and re-reads the session, which now
    // reports COMPLETED. Before EOP-105 that status was not even in the union, so
    // the lobby had no branch for it and the player was stranded on a screen with
    // neither a Start button (gated on LOBBY) nor a "game has started" notice
    // (gated on IN_PROGRESS). This pins the exit that replaced that dead end.
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ ...mockSession, status: 'COMPLETED' })
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(mockOnSessionEnd).toHaveBeenCalled();
    });
  });

  it('does not treat an in-progress session as completed', async () => {
    // Guards the branch ordering: the COMPLETED check sits immediately before the
    // IN_PROGRESS one-shot in refreshSession, so a test that only asserted
    // "COMPLETED exits" would still pass if the condition were inverted or widened
    // to every non-LOBBY status. IN_PROGRESS must fire onGameStarted and must not
    // eject the player.
    const mockOnGameStarted = vi.fn();
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockInProgressSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
        onGameStarted={mockOnGameStarted}
      />
    );

    await waitFor(() => {
      expect(mockOnGameStarted).toHaveBeenCalledWith(mockInProgressSession);
    });
    expect(mockOnSessionEnd).not.toHaveBeenCalled();
  });

  it('calls onSessionEnd when session returns 404 (ApiError with status 404)', async () => {
    // getSession throws ApiError(404, ...) — the shape api.ts actually produces
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      // Simulate a 404 problem-detail response from the backend
      return Promise.resolve({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: () => Promise.resolve({ title: 'Session not found', detail: 'No session matches that join code.' })
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(mockOnSessionEnd).toHaveBeenCalled();
    });
  });

  it('calls onSessionEnd when session returns 403 (ApiError with status 403)', async () => {
    // Simulate an expired/revoked token — backend returns 403 with a prose detail
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: () => Promise.resolve({ title: 'Forbidden', detail: 'The session has expired. Please start a new session.' })
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(mockOnSessionEnd).toHaveBeenCalled();
    });
  });

  it('calls handleStartGame and updates session on success', async () => {
    const startedSession: SessionStateDto = { ...mockSession, status: 'IN_PROGRESS' };
    const user = userEvent.setup();

    vi.stubGlobal('fetch', vi.fn((url: string, options?: RequestInit) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      if (url.includes('/start') && options?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(startedSession)
        } as Response);
      }
      if (url.includes('/deal') && options?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 204,
          json: () => Promise.resolve({})
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    // Wait for the Start game button to appear (facilitator + 3 players)
    const startButton = await screen.findByRole('button', { name: 'Start game' });
    await user.click(startButton);

    // After start, the IN_PROGRESS warning should appear
    await waitFor(() => {
      expect(screen.getByText('The game has started')).toBeInTheDocument();
    });
  });

  it('calls dealHands after startGame succeeds', async () => {
    const startedSession: SessionStateDto = { ...mockSession, status: 'IN_PROGRESS' };
    const user = userEvent.setup();
    let dealCallCount = 0;

    vi.stubGlobal('fetch', vi.fn((url: string, options?: RequestInit) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      if (url.includes('/start') && options?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(startedSession)
        } as Response);
      }
      if (url.includes('/deal') && options?.method === 'POST') {
        dealCallCount++;
        return Promise.resolve({
          ok: true,
          status: 204,
          json: () => Promise.resolve({})
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    const startButton = await screen.findByRole('button', { name: 'Start game' });
    await user.click(startButton);

    await waitFor(() => {
      expect(dealCallCount).toBe(1);
    });
  });

  it('shows error message when dealHands fails after startGame succeeds', async () => {
    const startedSession: SessionStateDto = { ...mockSession, status: 'IN_PROGRESS' };
    const user = userEvent.setup();

    vi.stubGlobal('fetch', vi.fn((url: string, options?: RequestInit) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      if (url.includes('/start') && options?.method === 'POST') {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve(startedSession)
        } as Response);
      }
      if (url.includes('/deal') && options?.method === 'POST') {
        return Promise.resolve({
          ok: false,
          status: 500,
          statusText: 'Internal Server Error',
          json: () => Promise.resolve({ title: 'Internal Server Error', detail: 'Failed to deal cards.' })
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    const startButton = await screen.findByRole('button', { name: 'Start game' });
    await user.click(startButton);

    // An error message should appear after dealHands fails
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
  });

  it('shows SSE data event triggers a session refresh', async () => {
    // Simulate an SSE stream that emits one data: event, then stays open
    const mockStream = new ReadableStream<Uint8Array>({
      start(controller) {
        // Emit a data: event after a tick
        setTimeout(() => {
          const encoder = new TextEncoder();
          controller.enqueue(encoder.encode('data: session-updated\n\n'));
        }, 10);
      }
    });

    let sessionCallCount = 0;
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          body: mockStream
        } as Response);
      }
      sessionCallCount++;
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    // Wait for initial load (first session call)
    await waitFor(() => {
      expect(screen.getByText('Game Lobby')).toBeInTheDocument();
    });

    // After the SSE data: event, a second session fetch should be triggered
    await waitFor(() => {
      expect(sessionCallCount).toBeGreaterThanOrEqual(2);
    });
  });

  it('calls onSessionEnd when SSE endpoint returns 403 (expired token via subscribeToSession onError)', async () => {
    // Initial getSession succeeds so the lobby renders, then the SSE endpoint
    // returns 403 — subscribeToSession calls onError(ApiError(403, ...)) which
    // LobbyScreen's onError handler forwards to onSessionEnd().
    let eventsCallCount = 0;
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        eventsCallCount++;
        // SSE endpoint returns 403 problem-detail (not a stream)
        return Promise.resolve({
          ok: false,
          status: 403,
          statusText: 'Forbidden',
          body: null,
          json: () => Promise.resolve({ title: 'Forbidden', detail: 'The session has expired. Please start a new session.' })
        } as Response);
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(mockOnSessionEnd).toHaveBeenCalled();
    });
    expect(eventsCallCount).toBeGreaterThanOrEqual(1);
  });

  it('shows game in progress message when session status is IN_PROGRESS', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/events')) {
        return new Promise(() => {});
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockInProgressSession)
      } as Response);
    }));

    render(
      <LobbyScreen
        sessionId={sessionId}
        playerId={playerId}
        playerToken={playerToken}
        onSessionEnd={mockOnSessionEnd}
      />
    );

    await waitFor(() => {
      expect(screen.getByText('The game has started')).toBeInTheDocument();
    });
  });
});
