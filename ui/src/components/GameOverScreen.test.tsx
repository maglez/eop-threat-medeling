import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameOverScreen } from './GameOverScreen';
import * as api from '../api';

// Mock the api module so no real fetch calls are made.
vi.mock('../api', async (importOriginal) => {
    const actual = await importOriginal<typeof api>();
    return {
        ...actual,
        getLeaderboard: vi.fn(),
        startNewGame: vi.fn(),
    };
});

// Mock ErrorSummary to keep assertions simple and avoid GOV.UK CSS dependencies.
vi.mock('./ErrorSummary', () => ({
    ErrorSummary: ({ title, errors }: { title: string; errors: string[] }) => (
        <div role="alert" data-testid="error-summary">
            <h2>{title}</h2>
            <ul>
                {errors.map((error) => (
                    <li key={error}>{error}</li>
                ))}
            </ul>
        </div>
    ),
}));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const SESSION_ID = 'session-abc';
const PLAYER_TOKEN = 'tok-xyz';

/** A minimal but structurally complete LeaderboardDto matching the api.ts types. */
const makeLeaderboard = (): api.LeaderboardDto => ({
    sessionStatus: 'COMPLETED',
    rows: [
        {
            playerId: 'player-1',
            seatOrder: 0,
            displayName: 'Alice',
            points: 10,
            position: 1,
            tied: false,
            capturedBySuit: {
                SPOOFING: 3,
                TAMPERING: 2,
                REPUDIATION: 1,
                INFORMATION_DISCLOSURE: 2,
                DENIAL_OF_SERVICE: 1,
                ELEVATION_OF_PRIVILEGE: 1,
            },
        },
        {
            playerId: 'player-2',
            seatOrder: 1,
            displayName: 'Bob',
            points: 7,
            position: 2,
            tied: false,
            capturedBySuit: {
                SPOOFING: 1,
                TAMPERING: 1,
                REPUDIATION: 2,
                INFORMATION_DISCLOSURE: 1,
                DENIAL_OF_SERVICE: 1,
                ELEVATION_OF_PRIVILEGE: 1,
            },
        },
    ],
});

const defaultProps = {
    sessionId: SESSION_ID,
    playerToken: PLAYER_TOKEN,
    isFacilitator: false,
    onNewGame: vi.fn(),
    onSessionEnd: vi.fn(),
};

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

describe('GameOverScreen', () => {
    const mockGetLeaderboard = vi.mocked(api.getLeaderboard);
    const mockStartNewGame = vi.mocked(api.startNewGame);

    beforeEach(() => {
        vi.resetAllMocks();
    });

    afterEach(() => {
        // Unconditionally restore real timers. The backoff tests below install
        // fake ones and restore them themselves, but a test that fails part-way
        // through would otherwise leak mocked timers into the next test.
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    // -----------------------------------------------------------------------
    // Test 1 — regression guard for fault 2: a 404 must NOT eject the client
    // -----------------------------------------------------------------------
    it('does not call onSessionEnd when the leaderboard returns 404', async () => {
        // Arrange
        const onSessionEnd = vi.fn();
        mockGetLeaderboard.mockRejectedValue(new api.ApiError(404, 'Game result not yet persisted'));

        // Act
        render(<GameOverScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

        // Assert — wait for the async load to settle, then confirm no ejection
        await waitFor(() => {
            expect(screen.getByTestId('error-summary')).toBeInTheDocument();
        });
        expect(onSessionEnd).not.toHaveBeenCalled();
    });

    // -----------------------------------------------------------------------
    // Test 2 — a 404 renders the error AND the retry button
    // -----------------------------------------------------------------------
    it('renders the error message and a Retry loading results button when the leaderboard returns 404', async () => {
        // Arrange
        mockGetLeaderboard.mockRejectedValue(new api.ApiError(404, 'Game result not yet persisted'));

        // Act
        render(<GameOverScreen {...defaultProps} />);

        // Assert
        await waitFor(() => {
            expect(screen.getByTestId('error-summary')).toBeInTheDocument();
        });
        expect(screen.getByRole('button', { name: 'Retry loading results' })).toBeInTheDocument();
    });

    // -----------------------------------------------------------------------
    // Test 3 — clicking Retry re-invokes the fetch; success removes the button
    // -----------------------------------------------------------------------
    it('re-invokes the leaderboard fetch on Retry and renders the leaderboard on success', async () => {
        // Arrange — first call fails with 404, second call succeeds
        const leaderboard = makeLeaderboard();
        mockGetLeaderboard
            .mockRejectedValueOnce(new api.ApiError(404, 'Not yet persisted'))
            .mockResolvedValueOnce(leaderboard);

        const user = userEvent.setup();

        // Act — initial render triggers the first (failing) fetch
        render(<GameOverScreen {...defaultProps} />);

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Retry loading results' })).toBeInTheDocument();
        });

        // The first call has already happened; record the count before clicking
        const callsBeforeRetry = mockGetLeaderboard.mock.calls.length;

        // Click Retry — triggers the second fetch
        await user.click(screen.getByRole('button', { name: 'Retry loading results' }));

        // Assert — call count increased and leaderboard is now rendered
        await waitFor(() => {
            expect(mockGetLeaderboard.mock.calls.length).toBeGreaterThan(callsBeforeRetry);
        });
        await waitFor(() => {
            expect(screen.getByText('Alice')).toBeInTheDocument();
        });
        expect(screen.getByText('Bob')).toBeInTheDocument();
        // Retry button must be gone once the leaderboard is loaded
        expect(screen.queryByRole('button', { name: 'Retry loading results' })).not.toBeInTheDocument();
    });

    // -----------------------------------------------------------------------
    // Test 3a — the retry pending state disables the button and announces itself
    //
    // Disabling the button is what actually prevents a double fetch: the
    // data-module="govuk-button" attribute is inert in this app, because
    // main.tsx imports only the compiled stylesheet and never calls initAll.
    // Because disabling drops focus, the label change is announced through a
    // role="status" region rather than by the button itself.
    // -----------------------------------------------------------------------
    it('disables the retry button and announces the retry while the refetch is in flight', async () => {
        // Arrange — first call fails; the retry hangs until we resolve it by hand
        let resolveRetry: (value: api.LeaderboardDto) => void = () => undefined;
        const pending = new Promise<api.LeaderboardDto>((resolve) => {
            resolveRetry = resolve;
        });
        mockGetLeaderboard
            .mockRejectedValueOnce(new api.ApiError(404, 'Not yet persisted'))
            .mockReturnValueOnce(pending);

        const user = userEvent.setup();

        render(<GameOverScreen {...defaultProps} />);

        const retryButton = await waitFor(() =>
            screen.getByRole('button', { name: 'Retry loading results' }),
        );
        expect(retryButton).not.toBeDisabled();

        // Act — click, but leave the fetch unresolved
        await user.click(retryButton);

        // Assert — the button is disabled, relabelled, and the status region speaks
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Retrying…' })).toBeDisabled();
        });
        expect(screen.getByRole('status')).toHaveTextContent('Retrying. Loading results.');

        // Resolve — the pending state clears and the leaderboard renders
        resolveRetry(makeLeaderboard());
        await waitFor(() => {
            expect(screen.getByText('Alice')).toBeInTheDocument();
        });
        expect(screen.queryByRole('button', { name: 'Retrying…' })).not.toBeInTheDocument();
    });

    // -----------------------------------------------------------------------
    // Test 3b — a double-click issues exactly one extra fetch
    //
    // GET /leaderboard recomputes a ScoreSheet from the whole trick history on
    // every call (ADR-030), so it is the most expensive read in the API. A
    // per-address limit now guards it server side (EOP-88, ADR-051); this guard
    // and the attempt cap below are the client half of the same concern.
    // -----------------------------------------------------------------------
    it('issues only one extra fetch when the retry button is double-clicked', async () => {
        // Arrange — first call fails; the retry hangs so the button stays pending
        let resolveRetry: (value: api.LeaderboardDto) => void = () => undefined;
        const pending = new Promise<api.LeaderboardDto>((resolve) => {
            resolveRetry = resolve;
        });
        mockGetLeaderboard
            .mockRejectedValueOnce(new api.ApiError(404, 'Not yet persisted'))
            .mockReturnValueOnce(pending);

        const user = userEvent.setup();

        render(<GameOverScreen {...defaultProps} />);

        const retryButton = await waitFor(() =>
            screen.getByRole('button', { name: 'Retry loading results' }),
        );
        const callsBeforeRetry = mockGetLeaderboard.mock.calls.length;

        // Act — click, then click again while the first retry is still in flight
        await user.click(retryButton);
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Retrying…' })).toBeDisabled();
        });
        await user.click(screen.getByRole('button', { name: 'Retrying…' }));

        // Assert — exactly one additional call, not two
        expect(mockGetLeaderboard.mock.calls.length).toBe(callsBeforeRetry + 1);

        resolveRetry(makeLeaderboard());
        await waitFor(() => {
            expect(screen.getByText('Alice')).toBeInTheDocument();
        });
    });

    // -----------------------------------------------------------------------
    // Test 4 — a 403 DOES eject the client (credential is dead)
    // -----------------------------------------------------------------------
    it('calls onSessionEnd when the leaderboard returns 403', async () => {
        // Arrange
        const onSessionEnd = vi.fn();
        mockGetLeaderboard.mockRejectedValue(new api.ApiError(403, 'Token invalid or expired'));

        // Act
        render(<GameOverScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

        // Assert
        await waitFor(() => {
            expect(onSessionEnd).toHaveBeenCalledTimes(1);
        });
    });

    // -----------------------------------------------------------------------
    // Test 5 — a 409 (game not yet COMPLETED) does NOT eject and shows error
    // -----------------------------------------------------------------------
    it('does not call onSessionEnd and shows the error when the leaderboard returns 409', async () => {
        // Arrange — 409 is thrown by GetLeaderboardUseCase when the session is not COMPLETED
        const onSessionEnd = vi.fn();
        mockGetLeaderboard.mockRejectedValue(new api.ApiError(409, 'Game is not yet completed'));

        // Act
        render(<GameOverScreen {...defaultProps} onSessionEnd={onSessionEnd} />);

        // Assert
        await waitFor(() => {
            expect(screen.getByTestId('error-summary')).toBeInTheDocument();
        });
        expect(onSessionEnd).not.toHaveBeenCalled();
        expect(screen.getByRole('button', { name: 'Retry loading results' })).toBeInTheDocument();
    });

    // -----------------------------------------------------------------------
    // Test 6 — happy path: leaderboard renders rows; retry button is absent
    // -----------------------------------------------------------------------
    it('renders the leaderboard rows and no retry button on a successful fetch', async () => {
        // Arrange
        const leaderboard = makeLeaderboard();
        mockGetLeaderboard.mockResolvedValue(leaderboard);

        // Act
        render(<GameOverScreen {...defaultProps} />);

        // Assert — both player rows are visible
        await waitFor(() => {
            expect(screen.getByText('Alice')).toBeInTheDocument();
        });
        expect(screen.getByText('Bob')).toBeInTheDocument();

        // Score totals
        expect(screen.getByText('10')).toBeInTheDocument();
        expect(screen.getByText('7')).toBeInTheDocument();

        // Position labels
        expect(screen.getByText('1st')).toBeInTheDocument();
        expect(screen.getByText('2nd')).toBeInTheDocument();

        // No error, no retry button
        expect(screen.queryByTestId('error-summary')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Retry loading results' })).not.toBeInTheDocument();
    });

    // -----------------------------------------------------------------------
    // Additional: facilitator sees "Start new game" button on success
    // -----------------------------------------------------------------------
    it('shows the Start new game button for a facilitator after a successful fetch', async () => {
        // Arrange
        mockGetLeaderboard.mockResolvedValue(makeLeaderboard());
        mockStartNewGame.mockResolvedValue(undefined);

        // Act
        render(<GameOverScreen {...defaultProps} isFacilitator={true} />);

        // Assert
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Start new game' })).toBeInTheDocument();
        });
    });

    // -----------------------------------------------------------------------
    // Additional: non-facilitator does NOT see "Start new game" button
    // -----------------------------------------------------------------------
    it('does not show the Start new game button for a non-facilitator', async () => {
        // Arrange
        mockGetLeaderboard.mockResolvedValue(makeLeaderboard());

        // Act
        render(<GameOverScreen {...defaultProps} isFacilitator={false} />);

        // Assert
        await waitFor(() => {
            expect(screen.getByText('Alice')).toBeInTheDocument();
        });
        expect(screen.queryByRole('button', { name: 'Start new game' })).not.toBeInTheDocument();
    });

    // -------------------------------------------------------------------------
    // 10. A failed retry arms a backoff cooldown before another attempt is allowed
    //
    //     EOP-88 asks for a bounded attempt cap or backoff on this button as
    //     defence in depth behind the server-side per-address limit (ADR-051).
    //     The cooldown is a chain of one-second timeouts, so the clock has to be
    //     faked -- and that rules userEvent out: userEvent.setup() awaits real
    //     delays inside RTL's async wrapper and deadlocks against mocked timers.
    //     fireEvent is synchronous and touches no timers, so the click is issued
    //     inside act() and each wait is flushed with the *async* timer advance,
    //     which drains microtasks between timers -- the synchronous variant would
    //     fire the first timeout and stop, leaving the chain stuck.
    // -------------------------------------------------------------------------
    it('disables the retry button for a backoff period after a failed retry', async () => {
        // Arrange
        vi.useFakeTimers();
        try {
            mockGetLeaderboard.mockRejectedValue(new api.ApiError(404, 'Not yet persisted'));

            render(<GameOverScreen {...defaultProps} />);
            await act(async () => {
                await vi.advanceTimersByTimeAsync(0);
            });

            // Act
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: 'Retry loading results' }));
                await vi.advanceTimersByTimeAsync(0);
            });

            // Assert -- the button counts the wait down and cannot be pressed
            expect(screen.getByRole('button', { name: 'Retry available in 1s' })).toBeDisabled();
            expect(screen.getByRole('status')).toHaveTextContent(
                'Retry failed. You can try again in 1 second. 4 attempts remaining.',
            );
            expect(mockGetLeaderboard).toHaveBeenCalledTimes(2);

            // Act -- let the cooldown expire
            await act(async () => {
                await vi.advanceTimersByTimeAsync(1000);
            });

            // Assert -- the button is offered again, and the wait ending is announced
            expect(screen.getByRole('button', { name: 'Retry loading results' })).toBeEnabled();
            expect(screen.getByRole('status')).toHaveTextContent('You can retry loading the results now.');
        } finally {
            vi.useRealTimers();
        }
    });

    // -------------------------------------------------------------------------
    // 11. The retry button disappears once the attempt cap is spent
    //
    //     Five attempts, with the backoff sequence 1s, 2s, 4s, 8s between them,
    //     so a human holding the button down spends 15 seconds of enforced
    //     waiting and then has to reload. The server limit is 300 reads per
    //     address per minute (EOP-88), so this cap is nowhere near it -- the
    //     point is that the client cannot contribute to exhausting it.
    // -------------------------------------------------------------------------
    it('stops offering a retry once the attempt cap is spent', async () => {
        // Arrange
        vi.useFakeTimers();
        try {
            mockGetLeaderboard.mockRejectedValue(new api.ApiError(404, 'Not yet persisted'));

            render(<GameOverScreen {...defaultProps} />);
            await act(async () => {
                await vi.advanceTimersByTimeAsync(0);
            });

            // Act -- attempts 1 to 4, each followed by its doubling cooldown
            for (const wait of [1, 2, 4, 8]) {
                // Two act() blocks, deliberately, and the zero-length advance in the
                // first one is load bearing. handleRetry is async, so the cooldown
                // timeout is only scheduled once the rejected request has settled;
                // advancing the full wait in the same block would move the clock
                // before that timer existed, leaving the countdown frozen at its
                // starting value and the button still disabled on the next pass.
                await act(async () => {
                    fireEvent.click(screen.getByRole('button', { name: 'Retry loading results' }));
                    await vi.advanceTimersByTimeAsync(0);
                });

                // One act() per second, not one for the whole wait. The cooldown is a
                // chain of one-second timeouts and each link is only scheduled by the
                // effect after React has re-rendered with the decremented count, which
                // does not happen until act() exits. Advancing the full wait in a
                // single block therefore fires exactly one tick.
                for (let tick = 0; tick < wait; tick += 1) {
                    await act(async () => {
                        await vi.advanceTimersByTimeAsync(1000);
                    });
                }
            }

            // Act -- the fifth and final attempt
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: 'Retry loading results' }));
                await vi.advanceTimersByTimeAsync(0);
            });

            // Assert -- no retry control remains, and the user is told what to do
            expect(screen.queryByRole('button', { name: /^Retry/ })).not.toBeInTheDocument();
            expect(
                screen.getByText('Retrying has not recovered the results. Reload the page to try again.'),
            ).toBeInTheDocument();
            expect(screen.getByRole('status')).toHaveTextContent(
                'Retry failed. No further attempts are available. Reload the page to try again.',
            );

            // One initial load on mount plus exactly five retries, and no more
            expect(mockGetLeaderboard).toHaveBeenCalledTimes(6);
        } finally {
            vi.useRealTimers();
        }
    });
});
