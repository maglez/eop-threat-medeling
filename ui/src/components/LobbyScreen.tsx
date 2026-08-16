import React, { useState, useEffect, useCallback } from 'react';
import { getSession, startGame, type SessionStateDto } from '../api';
import { ErrorSummary } from './ErrorSummary';
import type { PlayerDto } from '../api';

interface LobbyScreenProps {
  readonly sessionId: string;
  readonly playerId: string;
  readonly playerToken: string;
  readonly onSessionEnd: () => void;
}

/**
 * Lobby screen showing session details and players.
 * 
 * Displays the join code, player list, and start game button for facilitators.
 */
export function LobbyScreen({ sessionId, playerId, playerToken, onSessionEnd }: LobbyScreenProps): React.JSX.Element {
  const [session, setSession] = useState<SessionStateDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isStarting, setIsStarting] = useState(false);
  const [copied, setCopied] = useState(false);

  const currentPlayer = session?.players.find(p => p.playerId === playerId);
  const isFacilitator = currentPlayer?.role === 'FACILITATOR';
  const canStartGame = isFacilitator && session?.players.length !== undefined && session.players.length >= 2;

  const refreshSession = useCallback(async () => {
    try {
      const sessionData = await getSession(sessionId, playerToken);
      setSession(sessionData);
      setError(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load session';
      setError(message);
      
      // If it's a 403 or 404, the session is no longer accessible
      if (err instanceof Error && (message.includes('403') || message.includes('404'))) {
        onSessionEnd();
      }
    }
  }, [sessionId, playerToken, onSessionEnd]);

  // Initial load and setup SSE stream
  useEffect(() => {
    let abandoned = false;
    let eventSource: AbortController | null = null;

    const setupStream = async () => {
      // Initial load
      await refreshSession();
      if (abandoned) return;

      // Set up SSE stream
      eventSource = new AbortController();
      
      fetch(`/api/v1/sessions/${sessionId}/events`, {
        headers: { 
          'Accept': 'text/event-stream',
          'X-EoP-Player-Token': playerToken,
        },
        signal: eventSource.signal,
      }).then(async (res) => {
        if (!res.body) return;
        
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        
        try {
          while (!abandoned) {
            const { done, value } = await reader.read();
            if (done) break;
            
            const text = decoder.decode(value);
            if (text.includes('data:')) {
              // Session changed - re-fetch state
              if (!abandoned) {
                await refreshSession();
              }
            }
          }
        } catch (err) {
          // Stream closed or aborted - this is expected on unmount
          if (!abandoned) {
            console.warn('SSE stream error:', err);
          }
        }
      }).catch((err) => {
        // Connection failed - this is expected on unmount
        if (!abandoned) {
          console.warn('SSE connection failed:', err);
        }
      });
    };

    setupStream().catch(err => {
      if (!abandoned) {
        const message = err instanceof Error ? err.message : 'Failed to connect to session';
        setError(message);
      }
    });

    return () => {
      abandoned = true;
      if (eventSource) {
        eventSource.abort();
      }
    };
  }, [sessionId, playerToken, refreshSession]);

  const handleStartGame = async () => {
    if (!session) return;
    
    setIsStarting(true);
    
    try {
      const updatedSession = await startGame(sessionId, playerToken);
      setSession(updatedSession);
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
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy join code:', err);
    }
  };

  if (!session) {
    return (
      <div className="govuk-width-container">
        <main className="govuk-main-wrapper" id="main-content">
          <h1 className="govuk-heading-xl">Loading session...</h1>
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
                onClick={copyJoinCode}
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
                onClick={handleStartGame}
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