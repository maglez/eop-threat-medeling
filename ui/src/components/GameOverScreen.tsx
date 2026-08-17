import React, { useState, useEffect, useCallback } from 'react';
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
  readonly playerId: string;
  readonly playerToken: string;
  readonly isFacilitator: boolean;
  readonly onNewGame: () => void;
  readonly onSessionEnd: () => void;
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

  const loadLeaderboard = useCallback(async () => {
    try {
      const data = await getLeaderboard(sessionId, playerToken);
      setLeaderboard(data);
      setError(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load leaderboard';
      setError(message);
      if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
        onSessionEnd();
      }
    }
  }, [sessionId, playerToken, onSessionEnd]);

  useEffect(() => {
    void loadLeaderboard();
  }, [loadLeaderboard]);

  const handleNewGame = async () => {
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
