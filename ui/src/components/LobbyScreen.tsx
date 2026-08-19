import React, { useState, useEffect, useCallback, useRef } from 'react';
import { getSession, startGame, dealCards, subscribeToSession, ApiError, type SessionStateDto } from '../api';
import { ErrorSummary } from './ErrorSummary';
import type { PlayerDto } from '../api';

interface LobbyScreenProps {
  readonly sessionId: string;
  readonly playerId: string;
  readonly playerToken: string;
  readonly onSessionEnd: () => void;
  readonly onGameStarted?: (session: SessionStateDto) => void;
}

/**
 * Lobby screen showing session details and players.
 * 
 * Displays the join code, player list, and start game button for facilitators.
 */
export function LobbyScreen({ sessionId, playerId, playerToken, onSessionEnd, onGameStarted }: LobbyScreenProps): React.JSX.Element {
  const [session, setSession] = useState<SessionStateDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isStarting, setIsStarting] = useState(false);
  const [copied, setCopied] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Keep a stable ref to onGameStarted so it never appears in useCallback deps,
  // preventing the SSE subscription from tearing down and re-creating on every
  // render (which would hit ADR-034's per-session subscriber cap).
  const onGameStartedRef = useRef(onGameStarted);
  onGameStartedRef.current = onGameStarted;
  // Track whether we have already fired onGameStarted for this session so we
  // fire on the transition (LOBBY → IN_PROGRESS) rather than on every refresh
  // while the session is IN_PROGRESS.
  const gameStartedFiredRef = useRef(false);

  const currentPlayer = session?.players.find(p => p.playerId === playerId);
  const isFacilitator = currentPlayer?.role === 'FACILITATOR';
  const canStartGame = isFacilitator && session !== null && session.players.length >= 3;

  const refreshSession = useCallback(async () => {
    try {
      const sessionData = await getSession(sessionId, playerToken);
      setSession(sessionData);
      setError(null);
      
      // The lobby has no view for a session that is over, and `COMPLETED` is
      // reachable while a player is sitting here: the facilitator's end-session
      // endpoint sets it, and everyone else learns of it on the next doorbell
      // refresh. Leave the session rather than rendering a lobby that can never
      // progress — the same exit the 403/404 branch below takes.
      //
      // Home rather than the game-over screen, deliberately. A player still in
      // the lobby when the session completed never entered the game and has no
      // score on the leaderboard, so `onSessionEnd` (which clears the stored
      // token) is the honest destination. A player who *was* playing reaches
      // game-over by GameScreen's onGameOver callback instead, never by this
      // branch. See ADR-009 on the two lifecycle representations.
      //
      // The state setters above run first on purpose. If `onSessionEnd` ever
      // failed to unmount this component, rendering with `status === 'COMPLETED'`
      // shows a lobby with neither a Start button (gated on LOBBY) nor a
      // "game has started" notice (gated on IN_PROGRESS) — inert. Returning
      // before `setSession` would instead leave the *previous* state on screen,
      // which for a facilitator means a live Start button for a session that is
      // over. Inert beats misleading.
      //
      // `ABANDONED` deliberately gets no branch of its own. The expiry sweep is
      // the only code that writes it, and it deletes the row inside the same
      // transaction (`SessionRepository.abandonAndDelete`), so no client observes
      // it via that path — the next fetch is a 404, handled below. Nothing
      // structurally prevents a future second writer from leaving the row alive,
      // which is exactly why it is listed in `SESSION_STATUSES`: on the day that
      // happens, the gap surfaces as a missing branch on a type that already has
      // the member, rather than as an unmodelled string.
      if (sessionData.status === 'COMPLETED') {
        onSessionEnd();
        return;
      }

      // Fire onGameStarted exactly once, on the first refresh that sees IN_PROGRESS.
      // Reading through a ref keeps this callback stable (no dep on onGameStarted).
      if (sessionData.status === 'IN_PROGRESS' && !gameStartedFiredRef.current) {
        gameStartedFiredRef.current = true;
        onGameStartedRef.current?.(sessionData);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load session';
      setError(message);
      
      // If it's a 403 or 404, the session is no longer accessible — branch on
      // the numeric status carried by ApiError, not on the human-readable message.
      if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
        onSessionEnd();
      }
    }
  }, [sessionId, playerToken, onSessionEnd]);

  // Initial load and setup SSE stream
  useEffect(() => {
    let abandoned = false;

    const setup = async () => {
      // Initial load
      await refreshSession();
      if (abandoned) return;

      // Subscribe to session events via api.ts (fetch-based SSE, not EventSource —
      // EventSource cannot set custom headers; see ADR-015).
      const subscription = subscribeToSession(
        sessionId,
        playerToken,
        () => {
          // doorbell: a data: frame arrived — re-fetch session state
          if (!abandoned) {
            void refreshSession();
          }
        },
        (err) => {
          if (abandoned) return;
          const message = err instanceof Error ? err.message : 'Failed to connect to session';
          setError(message);
          if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
            onSessionEnd();
          }
        },
      );

      return () => {
        subscription.abort();
      };
    };

    let teardown: (() => void) | undefined;

    setup()
      .then((fn) => { teardown = fn; })
      .catch((err: unknown) => {
        if (!abandoned) {
          const message = err instanceof Error ? err.message : 'Failed to connect to session';
          setError(message);
        }
      });

    return () => {
      abandoned = true;
      teardown?.();
      // Clear any pending clipboard-feedback timer
      if (copyTimerRef.current !== null) {
        clearTimeout(copyTimerRef.current);
      }
    };
  }, [sessionId, playerToken, refreshSession, onSessionEnd]);

  const handleStartGame = async () => {
    if (!session) return;
    
    setIsStarting(true);
    
    try {
      const updatedSession = await startGame(sessionId, playerToken);
      setSession(updatedSession);
      // Deal cards immediately after the game starts so all players receive
      // their hands before GameScreen mounts and calls fetchHand / getTrickState.
      await dealCards(sessionId, playerToken);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to start game';
      setError(message);
    } finally {
      setIsStarting(false);
    }
  };

  const copyJoinCode = async () => {
    if (!session) return;
    
    try {
      await navigator.clipboard.writeText(session.joinCode);
      setCopied(true);
      // Clear any existing timer before setting a new one
      if (copyTimerRef.current !== null) {
        clearTimeout(copyTimerRef.current);
      }
      copyTimerRef.current = setTimeout(() => {
        copyTimerRef.current = null;
        setCopied(false);
      }, 2000);
    } catch (err) {
      console.error('Failed to copy join code:', err);
    }
  };

  if (!session) {
    return (
      <div className="govuk-width-container">
        <main className="govuk-main-wrapper" id="main-content">
          {error ? (
            <ErrorSummary
              title="Could not load session"
              errors={[error]}
              onDismiss={() => setError(null)}
            />
          ) : (
            <h1 className="govuk-heading-xl">Loading session...</h1>
          )}
        </main>
      </div>
    );
  }

  return (
    <div className="govuk-width-container">
      <main className="govuk-main-wrapper" id="main-content">
        <div className="govuk-grid-row">
          <div className="govuk-grid-column-two-thirds">
            {error && (
              <ErrorSummary 
                title="There is a problem" 
                errors={[error]} 
                onDismiss={() => setError(null)} 
              />
            )}
            
            <h1 className="govuk-heading-xl">Game Lobby</h1>
            
            <div className="govuk-inset-text">
              <p className="govuk-body-l">
                Share this code with other players: 
                <strong className="govuk-!-font-size-48 govuk-!-margin-left-2">
                  {session.joinCode}
                </strong>
              </p>
              <button 
                type="button"
                className="govuk-button govuk-button--secondary"
                onClick={() => { void copyJoinCode(); }}
              >
                {copied ? 'Copied!' : 'Copy code'}
              </button>
            </div>
            
            <h2 className="govuk-heading-m">Players ({session.players.length})</h2>
            
            <dl className="govuk-summary-list">
              {[...session.players]
                .sort((a: PlayerDto, b: PlayerDto) => a.seatOrder - b.seatOrder)
                .map((player: PlayerDto) => (
                  <div className="govuk-summary-list__row" key={player.playerId}>
                    <dt className="govuk-summary-list__key">
                      {player.displayName}
                      {player.role === 'FACILITATOR' && (
                        <strong className="govuk-tag govuk-tag--turquoise govuk-!-margin-left-2">
                          Facilitator
                        </strong>
                      )}
                    </dt>
                    <dd className="govuk-summary-list__value">
                      {player.connectionStatus === 'CONNECTED' ? (
                        <strong className="govuk-tag govuk-tag--green">Connected</strong>
                      ) : (
                        <strong className="govuk-tag govuk-tag--red">Disconnected</strong>
                      )}
                    </dd>
                  </div>
                ))
              }
            </dl>
            
            {isFacilitator && session.status === 'LOBBY' && (
              <button
                type="button"
                className="govuk-button"
                data-module="govuk-button"
                disabled={!canStartGame || isStarting}
                onClick={() => { void handleStartGame(); }}
              >
                {isStarting ? 'Starting game...' : 'Start game'}
              </button>
            )}
            
            {session.status === 'IN_PROGRESS' && (
              <div className="govuk-warning-text">
                <span className="govuk-warning-text__icon" aria-hidden="true">!</span>
                <strong className="govuk-warning-text__text">
                  <span className="govuk-warning-text__assistive">Warning</span>
                  The game has started
                </strong>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}