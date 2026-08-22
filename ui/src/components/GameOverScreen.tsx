import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  getLeaderboard,
  startNewGame,
  ApiError,
  type LeaderboardDto,
  type LeaderboardRowDto,
} from '../api';
import { ErrorSummary } from './ErrorSummary';

/** STRIDE category display labels in canonical order. */
const STRIDE_LABELS: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'SPOOFING', label: 'Spoofing' },
  { key: 'TAMPERING', label: 'Tampering' },
  { key: 'REPUDIATION', label: 'Repudiation' },
  { key: 'INFORMATION_DISCLOSURE', label: 'Info. Disclosure' },
  { key: 'DENIAL_OF_SERVICE', label: 'Denial of Service' },
  { key: 'ELEVATION_OF_PRIVILEGE', label: 'Elevation of Privilege' },
];

interface GameOverScreenProps {
  readonly sessionId: string;
  readonly playerToken: string;
  readonly isFacilitator: boolean;
  readonly onNewGame: () => void;
  readonly onSessionEnd: () => void;
}

/**
 * Maximum number of manual retries offered after the leaderboard fails to load.
 *
 * `GET /leaderboard` re-derives every score from the whole trick history on each
 * call (ADR-030), so it is the most expensive read in the application, and before
 * EOP-88 nothing bounded how often a client could ask for it. The server now
 * enforces a per-address read limit (ADR-051), which is the control; this cap is
 * defence in depth on the one control the client actually owns — a human holding
 * down a retry button. Five attempts spread over the backoff below is more than
 * enough for the race this button exists for (the game-completed event arriving
 * before the result row is persisted, EOP-86), and far short of the server limit.
 */
const MAX_RETRY_ATTEMPTS = 5;

/** Ceiling on the exponential backoff, in seconds. */
const RETRY_BACKOFF_MAX_SECONDS = 16;

/** Countdown tick interval, in milliseconds. */
const COOLDOWN_TICK_MS = 1000;

/**
 * Seconds to wait before the nth retry is offered again: 1, 2, 4, 8, 16.
 *
 * Five attempts therefore span 31 seconds of enforced waiting, which is what
 * turns a held-down button into a trickle rather than a flood.
 */
function backoffSecondsFor(attempt: number): number {
  return Math.min(2 ** (attempt - 1), RETRY_BACKOFF_MAX_SECONDS);
}

/**
 * Game Over screen: shows the final leaderboard with STRIDE breakdown per player.
 * Facilitators see a "Start new game" button that re-deals the same deck to the
 * same seats.
 *
 * Accessible: uses a GOV.UK-styled summary table with scope attributes and a
 * visually-hidden caption. Positions use ordinal suffixes for screen readers.
 */
export function GameOverScreen({
  sessionId,
  playerToken,
  isFacilitator,
  onNewGame,
  onSessionEnd,
}: GameOverScreenProps): React.JSX.Element {
  const [leaderboard, setLeaderboard] = useState<LeaderboardDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isStartingNewGame, setIsStartingNewGame] = useState(false);
  const [isRetrying, setIsRetrying] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [cooldownSeconds, setCooldownSeconds] = useState(0);
  const [retryAnnouncement, setRetryAnnouncement] = useState('');
  const cooldownWasActive = useRef(false);

  const loadLeaderboard = useCallback(async (): Promise<boolean> => {
    try {
      const data = await getLeaderboard(sessionId, playerToken);
      setLeaderboard(data);
      setError(null);
      return true;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load leaderboard';
      setError(message);
      // Only eject on 403 (token invalid/expired). A 404 here means the game
      // result has not been persisted yet (race between the game-completed SSE
      // event and the async persist write) — keep the user on the leaderboard
      // screen so they can retry rather than silently sending them home.
      if (err instanceof ApiError && err.status === 403) {
        onSessionEnd();
      }
      return false;
    }
  }, [sessionId, playerToken, onSessionEnd]);

  useEffect(() => {
    void loadLeaderboard();
  }, [loadLeaderboard]);

  // Ticks the retry cooldown down once per second, then announces that the
  // button is live again. The countdown is rendered in the button label, which
  // a screen reader never reaches because the button is disabled while it runs,
  // so the end of the wait is announced through the live region instead of
  // being read out second by second.
  useEffect(() => {
    if (cooldownSeconds > 0) {
      cooldownWasActive.current = true;
      const timer = setTimeout(() => {
        setCooldownSeconds((seconds) => seconds - 1);
      }, COOLDOWN_TICK_MS);
      return () => { clearTimeout(timer); };
    }
    if (cooldownWasActive.current) {
      cooldownWasActive.current = false;
      setRetryAnnouncement('You can retry loading the results now.');
    }
    return undefined;
  }, [cooldownSeconds]);

  const attemptsRemaining = MAX_RETRY_ATTEMPTS - retryCount;
  const canRetry = attemptsRemaining > 0 && cooldownSeconds === 0 && !isRetrying;

  // Guards the retry button against a double-click issuing two concurrent
  // fetches, and bounds how many times it can be pressed at all. The attempt
  // is counted before the request goes out, so a click always consumes an
  // allowance whatever the outcome. loadLeaderboard handles its own errors and
  // reports success as a boolean, so the finally always clears the pending
  // state and a failure always arms the backoff.
  const handleRetry = async (): Promise<void> => {
    if (!canRetry) return;
    setIsRetrying(true);
    setRetryAnnouncement('Retrying. Loading results.');
    const attempt = retryCount + 1;
    setRetryCount(attempt);
    try {
      const succeeded = await loadLeaderboard();
      if (succeeded) {
        setRetryAnnouncement('');
        return;
      }
      const remaining = MAX_RETRY_ATTEMPTS - attempt;
      if (remaining === 0) {
        setRetryAnnouncement(
          'Retry failed. No further attempts are available. Reload the page to try again.',
        );
        return;
      }
      const wait = backoffSecondsFor(attempt);
      setCooldownSeconds(wait);
      setRetryAnnouncement(
        `Retry failed. You can try again in ${wait} ${wait === 1 ? 'second' : 'seconds'}. ` +
          `${remaining} ${remaining === 1 ? 'attempt' : 'attempts'} remaining.`,
      );
    } finally {
      setIsRetrying(false);
    }
  };

  const handleNewGame = async (): Promise<void> => {
    if (isStartingNewGame) return;
    setIsStartingNewGame(true);
    try {
      await startNewGame(sessionId, playerToken);
      onNewGame();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to start new game';
      setError(message);
    } finally {
      setIsStartingNewGame(false);
    }
  };

  return (
    <div className="govuk-width-container">
      <main className="govuk-main-wrapper" id="main-content">
        <h1 className="govuk-heading-xl">Game over</h1>

        {error && (
          <ErrorSummary
            title="There is a problem"
            errors={[error]}
          />
        )}

        {error && !leaderboard && (
          <>
            {attemptsRemaining > 0 ? (
              <button
                type="button"
                className="govuk-button govuk-button--secondary"
                data-module="govuk-button"
                disabled={!canRetry}
                aria-disabled={!canRetry}
                onClick={() => { void handleRetry(); }}
              >
                {isRetrying
                  ? 'Retrying…'
                  : cooldownSeconds > 0
                    ? `Retry available in ${cooldownSeconds}s`
                    : 'Retry loading results'}
              </button>
            ) : (
              <p className="govuk-body">
                Retrying has not recovered the results. Reload the page to try again.
              </p>
            )}

            {/*
              Disabling the button removes it from the tab order and drops focus,
              so the label flipping to 'Retrying…' is announced to nobody. The
              'Loading results…' region below only renders while there is no
              error, which is never true on this branch, so a screen-reader user
              would otherwise get silence between the click and the outcome.
            */}
            <p className="govuk-visually-hidden" role="status" aria-live="polite" aria-atomic="true">
              {retryAnnouncement}
            </p>
          </>
        )}

        {leaderboard ? (
          <>
            <h2 className="govuk-heading-l">Final leaderboard</h2>

            <div className="govuk-!-overflow-auto">
              <table className="govuk-table" aria-label="Final leaderboard">
                <caption className="govuk-table__caption govuk-visually-hidden">
                  Final scores with STRIDE category breakdown
                </caption>
                <thead className="govuk-table__head">
                  <tr className="govuk-table__row">
                    <th scope="col" className="govuk-table__header">Position</th>
                    <th scope="col" className="govuk-table__header">Player</th>
                    <th scope="col" className="govuk-table__header govuk-table__header--numeric">Total</th>
                    {STRIDE_LABELS.map(({ key, label }) => (
                      <th
                        key={key}
                        scope="col"
                        className="govuk-table__header govuk-table__header--numeric"
                        title={key.replace(/_/g, ' ')}
                      >
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="govuk-table__body">
                  {leaderboard.rows.map((row) => (
                    <LeaderboardRow key={row.playerId} row={row} />
                  ))}
                </tbody>
              </table>
            </div>

            <div className="govuk-button-group govuk-!-margin-top-6">
              {isFacilitator && (
                <button
                  type="button"
                  className="govuk-button"
                  data-module="govuk-button"
                  disabled={isStartingNewGame}
                  aria-disabled={isStartingNewGame}
                  onClick={() => { void handleNewGame(); }}
                >
                  {isStartingNewGame ? 'Starting…' : 'Start new game'}
                </button>
              )}
              <button
                type="button"
                className="govuk-button govuk-button--secondary"
                data-module="govuk-button"
                onClick={onSessionEnd}
              >
                Leave session
              </button>
            </div>
          </>
        ) : (
          !error && (
            <p className="govuk-body" aria-live="polite">
              Loading results…
            </p>
          )
        )}
      </main>
    </div>
  );
}

// ---- Sub-components ----

interface LeaderboardRowProps {
  readonly row: LeaderboardRowDto;
}

function ordinalSuffix(n: number): string {
  const mod100 = n % 100;
  if (mod100 >= 11 && mod100 <= 13) return `${n}th`;
  switch (n % 10) {
    case 1: return `${n}st`;
    case 2: return `${n}nd`;
    case 3: return `${n}rd`;
    default: return `${n}th`;
  }
}

function LeaderboardRow({ row }: LeaderboardRowProps): React.JSX.Element {
  const positionLabel = `${ordinalSuffix(row.position)}${row.tied ? ' (tied)' : ''}`;

  return (
    <tr className="govuk-table__row">
      <td className="govuk-table__cell">
        <span aria-label={`${positionLabel} place`}>{positionLabel}</span>
      </td>
      <td className="govuk-table__cell">{row.displayName}</td>
      <td className="govuk-table__cell govuk-table__cell--numeric govuk-!-font-weight-bold">
        {row.points}
      </td>
      {STRIDE_LABELS.map(({ key }) => (
        <td key={key} className="govuk-table__cell govuk-table__cell--numeric">
          {row.capturedBySuit[key] ?? 0}
        </td>
      ))}
    </tr>
  );
}
