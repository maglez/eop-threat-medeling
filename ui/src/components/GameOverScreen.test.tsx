import { render, screen, waitFor } from '@testing-library/react';
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
                {errors.map((error, index) => (
                    <li key={index}>{error}</li>
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
    playerId: 'player-1',
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
    // GET /leaderboard has no rate limit at any layer (EOP-88) and recomputes
    // a ScoreSheet on every call, so the client must not amplify it.
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
});
