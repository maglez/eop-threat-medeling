import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  fetchHand,
  getTrickState,
  playCard,
  resolveTrick,
  getSession,
  subscribeToSession,
  ApiError,
  type HandDto,
  type CardDto,
  type TrickStateDto,
  type SessionStateDto,
  type PlayerDto,
} from '../api';
import { ErrorSummary } from './ErrorSummary';
import './GameScreen.css';

interface GameScreenProps {
  readonly sessionId: string;
  readonly playerId: string;
  readonly playerToken: string;
  readonly session: SessionStateDto;
  readonly onSessionEnd: () => void;
}

/** Positions for other players around the table relative to the current player. */
type TablePosition = 'top' | 'top-left' | 'top-right' | 'left' | 'right';

/**
 * Maps other players (by their index relative to the current player's seat) to
 * a table position. Supports 2–5 other players (3–6 total).
 */
function getTablePositions(otherCount: number): TablePosition[] {
  switch (otherCount) {
    case 1: return ['top'];
    case 2: return ['top-left', 'top-right'];
    case 3: return ['top-left', 'top', 'top-right'];
    case 4: return ['left', 'top-left', 'top-right', 'right'];
    default: return ['left', 'top-left', 'top', 'top-right', 'right'];
  }
}

/** Suit colour mapping for card faces — GOV.UK palette. */
const SUIT_COLOURS: Record<string, string> = {
  SPOOFING: '#1d70b8',           // govuk-blue
  TAMPERING: '#00703c',          // govuk-green
  REPUDIATION: '#4c2c92',        // govuk-purple
  INFORMATION_DISCLOSURE: '#d4351c', // govuk-red
  DENIAL_OF_SERVICE: '#f47738',  // govuk-orange
  ELEVATION_OF_PRIVILEGE: '#0b0c0c', // govuk-black
};

const SUIT_LABELS: Record<string, string> = {
  SPOOFING: 'S',
  TAMPERING: 'T',
  REPUDIATION: 'R',
  INFORMATION_DISCLOSURE: 'I',
  DENIAL_OF_SERVICE: 'D',
  ELEVATION_OF_PRIVILEGE: 'E',
};

// ---- Sub-components ----

interface CardFaceProps {
  readonly card: CardDto;
  readonly selected: boolean;
  readonly disabled: boolean;
  readonly dragging: boolean;
  readonly onSelect: () => void;
  readonly onPointerDown: (e: React.PointerEvent<HTMLDivElement>) => void;
}

function CardFace({ card, selected, disabled, dragging, onSelect, onPointerDown }: CardFaceProps): React.JSX.Element {
  const suitColour = SUIT_COLOURS[card.suit] ?? '#0b0c0c';
  const suitLabel = SUIT_LABELS[card.suit] ?? card.suit[0];

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (!disabled && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      onSelect();
    }
  };

  return (
    <div
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
      aria-pressed={selected}
      aria-label={`${card.rankSymbol} of ${card.suit.toLowerCase().replace(/_/g, ' ')}: ${card.threatPrompt}`}
      onClick={disabled ? undefined : onSelect}
      onKeyDown={handleKeyDown}
      onPointerDown={disabled ? undefined : onPointerDown}
      style={{
        display: 'inline-flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        width: '80px',
        height: '120px',
        border: selected ? '3px solid #ffdd00' : '2px solid #b1b4b6',
        borderRadius: '6px',
        padding: '6px',
        backgroundColor: '#ffffff',
        cursor: disabled ? 'not-allowed' : dragging ? 'grabbing' : 'grab',
        opacity: disabled ? 0.45 : 1,
        boxShadow: selected ? '0 0 0 3px #ffdd00' : dragging ? '4px 4px 12px rgba(0,0,0,0.3)' : '1px 1px 4px rgba(0,0,0,0.15)',
        userSelect: 'none',
        touchAction: 'none',
        transition: 'box-shadow 0.1s, opacity 0.1s',
        outline: 'none',
        flexShrink: 0,
      }}
    >
      <div style={{ fontSize: '14px', fontWeight: 'bold', color: suitColour }}>
        {card.rankSymbol}
      </div>
      <div style={{
        fontSize: '11px',
        color: suitColour,
        fontWeight: 'bold',
        textAlign: 'center',
        lineHeight: 1,
      }}>
        {suitLabel}
      </div>
      <div style={{ fontSize: '14px', fontWeight: 'bold', color: suitColour, textAlign: 'right' }}>
        {card.rankSymbol}
      </div>
    </div>
  );
}

function CardBack(): React.JSX.Element {
  return (
    <div style={{
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: '80px',
      height: '120px',
      border: '2px solid #b1b4b6',
      borderRadius: '6px',
      backgroundColor: '#1d70b8',
      color: '#ffffff',
      fontSize: '12px',
      fontWeight: 'bold',
      userSelect: 'none',
    }}
      aria-hidden="true"
    >
      EoP
    </div>
  );
}

interface OtherPlayerSeatProps {
  readonly player: PlayerDto;
  readonly position: TablePosition;
}

function OtherPlayerSeat({ player }: OtherPlayerSeatProps): React.JSX.Element {
  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: '6px',
    }}>
      <span className="govuk-body-s" style={{ fontWeight: 'bold', textAlign: 'center', maxWidth: '100px', wordBreak: 'break-word' }}>
        {player.displayName}
      </span>
      <CardBack />
    </div>
  );
}

// ---- Drag state ----

interface DragState {
  readonly cardId: string;
  readonly startX: number;
  readonly startY: number;
  readonly currentX: number;
  readonly currentY: number;
}

// ---- Main component ----

/**
 * Game screen: virtual table layout with private hand, drag-and-drop card play,
 * and trick winner announcement.
 *
 * SSE doorbell pattern mirrors LobbyScreen — each event triggers a re-fetch of
 * trick state and session state. playerToken is sent as a header only, never in
 * URL or body (ADR-015).
 */
export function GameScreen({
  sessionId,
  playerId,
  playerToken,
  session: initialSession,
  onSessionEnd,
}: GameScreenProps): React.JSX.Element {
  const [session, setSession] = useState<SessionStateDto>(initialSession);
  const [hand, setHand] = useState<HandDto | null>(null);
  const [trickState, setTrickState] = useState<TrickStateDto | null>(null);
  const [selectedCardId, setSelectedCardId] = useState<string | null>(null);
  const [dragState, setDragState] = useState<DragState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPlayingCard, setIsPlayingCard] = useState(false);
  const [winnerDismissed, setWinnerDismissed] = useState(false);
  const winnerTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const dropZoneRef = useRef<HTMLDivElement | null>(null);

  const currentPlayer = session.players.find(p => p.playerId === playerId);
  const mySeats = currentPlayer?.seatOrder;

  // Determine if it's my turn
  const isMyTurn = trickState?.seatToPlay !== undefined && mySeats !== undefined
    ? trickState.seatToPlay === mySeats
    : false;

  // Trick winner info
  const winnerSeat = trickState?.trick?.winningSeat;
  const winnerPlayer = winnerSeat !== undefined
    ? session.players.find(p => p.seatOrder === winnerSeat)
    : undefined;
  const winningPlay = trickState?.trick?.plays.find(p => p.seatOrder === winnerSeat);
  const iAmWinner = winnerSeat !== undefined && mySeats !== undefined && winnerSeat === mySeats;
  const showWinnerBanner = winnerSeat !== undefined && !winnerDismissed && trickState?.complete === true;

  // Reset winner dismissed state when trick changes
  const trickId = trickState?.trick?.trickId;
  useEffect(() => {
    setWinnerDismissed(false);
  }, [trickId]);

  // Auto-dismiss winner banner after 5 seconds
  useEffect(() => {
    if (showWinnerBanner) {
      if (winnerTimerRef.current !== null) clearTimeout(winnerTimerRef.current);
      winnerTimerRef.current = setTimeout(() => {
        setWinnerDismissed(true);
        winnerTimerRef.current = null;
      }, 5000);
    }
    return () => {
      if (winnerTimerRef.current !== null) {
        clearTimeout(winnerTimerRef.current);
        winnerTimerRef.current = null;
      }
    };
  }, [showWinnerBanner]);

  const refreshGameState = useCallback(async () => {
    try {
      const [newHand, newTrickState, newSession] = await Promise.all([
        fetchHand(sessionId, playerToken),
        getTrickState(sessionId, playerToken),
        getSession(sessionId, playerToken),
      ]);
      setHand(newHand);
      setTrickState(newTrickState);
      setSession(newSession);
      setError(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load game state';
      setError(message);
      if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
        onSessionEnd();
      }
    }
  }, [sessionId, playerToken, onSessionEnd]);

  // Initial load + SSE subscription
  useEffect(() => {
    let abandoned = false;

    const setup = async () => {
      await refreshGameState();
      if (abandoned) return;

      const subscription = subscribeToSession(
        sessionId,
        playerToken,
        () => { if (!abandoned) void refreshGameState(); },
        (err) => {
          if (abandoned) return;
          const message = err instanceof Error ? err.message : 'Connection lost';
          setError(message);
          if (err instanceof ApiError && (err.status === 403 || err.status === 404)) {
            onSessionEnd();
          }
        },
      );

      return () => { subscription.abort(); };
    };

    let teardown: (() => void) | undefined;
    setup()
      .then((fn) => { teardown = fn; })
      .catch((err: unknown) => {
        if (!abandoned) {
          setError(err instanceof Error ? err.message : 'Failed to connect');
        }
      });

    return () => {
      abandoned = true;
      teardown?.();
      if (winnerTimerRef.current !== null) clearTimeout(winnerTimerRef.current);
    };
  }, [sessionId, playerToken, refreshGameState, onSessionEnd]);

  // ---- Card play ----

  const submitCardPlay = useCallback(async (cardId: string) => {
    if (isPlayingCard) return;
    setIsPlayingCard(true);
    setSelectedCardId(null);
    try {
      await playCard(sessionId, playerToken, { cardId });
      await refreshGameState();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to play card';
      setError(message);
    } finally {
      setIsPlayingCard(false);
    }
  }, [sessionId, playerToken, isPlayingCard, refreshGameState]);

  const handlePlaySelected = () => {
    if (selectedCardId) void submitCardPlay(selectedCardId);
  };

  // ---- Drag-and-drop (pointer events) ----

  const handlePointerDown = (cardId: string) => (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isMyTurn || isPlayingCard) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragState({
      cardId,
      startX: e.clientX,
      startY: e.clientY,
      currentX: e.clientX,
      currentY: e.clientY,
    });
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragState) return;
    setDragState(prev => prev ? { ...prev, currentX: e.clientX, currentY: e.clientY } : null);
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragState) return;
    const dropZone = dropZoneRef.current;
    if (dropZone) {
      const rect = dropZone.getBoundingClientRect();
      const inDropZone =
        e.clientX >= rect.left &&
        e.clientX <= rect.right &&
        e.clientY >= rect.top &&
        e.clientY <= rect.bottom;
      if (inDropZone) {
        void submitCardPlay(dragState.cardId);
      }
    }
    setDragState(null);
  };

  const handlePointerCancel = () => { setDragState(null); };

  // ---- Resolve trick / start next ----

  const handleStartNextTrick = async () => {
    try {
      await resolveTrick(sessionId, playerToken);
      setWinnerDismissed(true);
      await refreshGameState();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to start next trick';
      setError(message);
    }
  };

  // ---- Layout helpers ----

  const otherPlayers = [...session.players]
    .filter(p => p.playerId !== playerId)
    .sort((a, b) => {
      // Sort clockwise from current player's seat.
      // Use ((x % n) + n) % n to correctly normalise negative values for any n.
      const n = session.players.length;
      const base = mySeats ?? 0;
      const aNorm = ((a.seatOrder - base) % n + n) % n;
      const bNorm = ((b.seatOrder - base) % n + n) % n;
      return aNorm - bNorm;
    });

  const positions = getTablePositions(otherPlayers.length);

  // Position styles for other players
  const positionStyle: Record<TablePosition, React.CSSProperties> = {
    'top': { gridColumn: '2', gridRow: '1', justifySelf: 'center' },
    'top-left': { gridColumn: '1', gridRow: '1', justifySelf: 'end' },
    'top-right': { gridColumn: '3', gridRow: '1', justifySelf: 'start' },
    'left': { gridColumn: '1', gridRow: '2', alignSelf: 'center', justifySelf: 'end' },
    'right': { gridColumn: '3', gridRow: '2', alignSelf: 'center', justifySelf: 'start' },
  };

  // Whose turn label
  const seatToPlayPlayer = trickState?.seatToPlay !== undefined
    ? session.players.find(p => p.seatOrder === trickState.seatToPlay)
    : undefined;
  const turnLabel = seatToPlayPlayer
    ? (seatToPlayPlayer.playerId === playerId ? 'Your turn' : `${seatToPlayPlayer.displayName}'s turn`)
    : '';

  if (!hand || !trickState) {
    return (
      <div className="govuk-width-container">
        <main className="govuk-main-wrapper" id="main-content">
          {error ? (
            <ErrorSummary title="Could not load game" errors={[error]} onDismiss={() => setError(null)} />
          ) : (
            <p className="govuk-body">Loading game...</p>
          )}
        </main>
      </div>
    );
  }

  return (
    <div className="govuk-width-container">
      <main className="govuk-main-wrapper" id="main-content"
        onPointerMove={dragState ? handlePointerMove : undefined}
        onPointerUp={dragState ? handlePointerUp : undefined}
        onPointerCancel={dragState ? handlePointerCancel : undefined}
      >
        {error && (
          <ErrorSummary title="There is a problem" errors={[error]} onDismiss={() => setError(null)} />
        )}

        {/* Turn announcement for screen readers */}
        <div
          aria-live="polite"
          aria-atomic="true"
          className="govuk-visually-hidden"
        >
          {turnLabel}
        </div>

        {/* Trick winner banner */}
        {showWinnerBanner && winnerPlayer && (
          <div
            role="alert"
            className="govuk-notification-banner govuk-notification-banner--success"
            style={{ marginBottom: '20px' }}
          >
            <div className="govuk-notification-banner__header">
              <h2 className="govuk-notification-banner__title">Trick won!</h2>
            </div>
            <div className="govuk-notification-banner__content">
              <p className="govuk-body">
                <strong>Trick won by {winnerPlayer.displayName}</strong>
                {winningPlay && (
                  <> — {winningPlay.card.rankSymbol} of {winningPlay.card.suit.toLowerCase().replace(/_/g, ' ')}</>
                )}
              </p>
              <div className="govuk-button-group">
                {iAmWinner && (
                  <button
                    type="button"
                    className="govuk-button"
                    onClick={() => { void handleStartNextTrick(); }}
                  >
                    Start next trick
                  </button>
                )}
                <button
                  type="button"
                  className="govuk-button govuk-button--secondary"
                  onClick={() => setWinnerDismissed(true)}
                >
                  Dismiss
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Turn indicator (visible) */}
        {turnLabel && (
          <p className="govuk-body govuk-!-font-weight-bold" aria-hidden="true">
            {turnLabel}
          </p>
        )}

        {/* Table grid: 3 columns × 3 rows */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: '1fr 2fr 1fr',
          gridTemplateRows: 'auto auto auto',
          gap: '16px',
          marginBottom: '24px',
        }}>
          {/* Other players */}
          {otherPlayers.map((player, idx) => {
            const pos = positions[idx] ?? 'top';
            return (
              <div key={player.playerId} style={positionStyle[pos]}>
                <OtherPlayerSeat
                  player={player}
                  position={pos}
                />
              </div>
            );
          })}

          {/* Central trick zone */}
          <div
            ref={dropZoneRef}
            style={{
              gridColumn: '2',
              gridRow: '2',
              minHeight: '160px',
              border: dragState ? '3px dashed #1d70b8' : '2px dashed #b1b4b6',
              borderRadius: '8px',
              padding: '16px',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: '12px',
              backgroundColor: dragState ? '#e8f0fb' : '#f3f2f1',
              transition: 'background-color 0.15s, border-color 0.15s',
            }}
            aria-label="Current trick — drop a card here to play it"
          >
            <span className="govuk-body-s govuk-hint">Current trick</span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', justifyContent: 'center' }}>
              {(trickState.trick?.plays ?? []).map(play => {
                const playedByPlayer = session.players.find(p => p.playerId === play.playerId);
                return (
                  <div key={play.trickPlayId} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
                    <span className="govuk-body-s" style={{ fontSize: '11px' }}>
                      {playedByPlayer?.displayName ?? 'Unknown'}
                    </span>
                    <div style={{
                      display: 'inline-flex',
                      flexDirection: 'column',
                      justifyContent: 'space-between',
                      width: '64px',
                      height: '96px',
                      border: '2px solid #b1b4b6',
                      borderRadius: '6px',
                      padding: '4px',
                      backgroundColor: '#ffffff',
                    }}>
                      <div style={{ fontSize: '12px', fontWeight: 'bold', color: SUIT_COLOURS[play.card.suit] ?? '#0b0c0c' }}>
                        {play.card.rankSymbol}
                      </div>
                      <div style={{ fontSize: '10px', color: SUIT_COLOURS[play.card.suit] ?? '#0b0c0c', textAlign: 'center', fontWeight: 'bold' }}>
                        {SUIT_LABELS[play.card.suit] ?? play.card.suit[0]}
                      </div>
                      <div style={{ fontSize: '12px', fontWeight: 'bold', color: SUIT_COLOURS[play.card.suit] ?? '#0b0c0c', textAlign: 'right' }}>
                        {play.card.rankSymbol}
                      </div>
                    </div>
                  </div>
                );
              })}
              {(trickState.trick?.plays.length ?? 0) === 0 && (
                <span className="govuk-hint" style={{ fontSize: '13px' }}>No cards played yet</span>
              )}
            </div>
          </div>
        </div>

        {/* Current player's hand */}
        <div style={{ borderTop: '2px solid #b1b4b6', paddingTop: '16px' }}>
          <p className="govuk-body govuk-!-font-weight-bold" style={{ marginBottom: '8px' }}>
            {currentPlayer?.displayName ?? 'Your hand'}
          </p>
          <div
            role="group"
            aria-label="Your hand"
            style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', marginBottom: '16px' }}
          >
            {hand.cards.map(card => (
              <CardFace
                key={card.cardId}
                card={card}
                selected={selectedCardId === card.cardId}
                disabled={!isMyTurn || isPlayingCard}
                dragging={dragState?.cardId === card.cardId}
                onSelect={() => {
                  if (!isMyTurn || isPlayingCard) return;
                  setSelectedCardId(prev => prev === card.cardId ? null : card.cardId);
                }}
                onPointerDown={handlePointerDown(card.cardId)}
              />
            ))}
            {hand.cards.length === 0 && (
              <span className="govuk-hint">No cards remaining</span>
            )}
          </div>

          {/* Keyboard fallback: play selected card button */}
          {selectedCardId && isMyTurn && (
            <button
              type="button"
              className="govuk-button"
              disabled={isPlayingCard}
              onClick={handlePlaySelected}
            >
              {isPlayingCard ? 'Playing...' : 'Play selected card'}
            </button>
          )}

          {!isMyTurn && trickState.seatToPlay !== undefined && (
            <p className="govuk-hint">
              Waiting for {seatToPlayPlayer?.displayName ?? 'another player'} to play
            </p>
          )}
        </div>

        {/* Drag ghost */}
        {dragState && (() => {
          const draggedCard = hand.cards.find(c => c.cardId === dragState.cardId);
          if (!draggedCard) return null;
          const dx = dragState.currentX - dragState.startX;
          const dy = dragState.currentY - dragState.startY;
          return (
            <div
              aria-hidden="true"
              style={{
                position: 'fixed',
                left: dragState.startX + dx - 40,
                top: dragState.startY + dy - 60,
                pointerEvents: 'none',
                zIndex: 1000,
                opacity: 0.85,
                transform: 'rotate(5deg)',
              }}
            >
              <div style={{
                display: 'inline-flex',
                flexDirection: 'column',
                justifyContent: 'space-between',
                width: '80px',
                height: '120px',
                border: '2px solid #b1b4b6',
                borderRadius: '6px',
                padding: '6px',
                backgroundColor: '#ffffff',
                boxShadow: '4px 4px 12px rgba(0,0,0,0.3)',
              }}>
                <div style={{ fontSize: '14px', fontWeight: 'bold', color: SUIT_COLOURS[draggedCard.suit] ?? '#0b0c0c' }}>
                  {draggedCard.rankSymbol}
                </div>
                <div style={{ fontSize: '11px', color: SUIT_COLOURS[draggedCard.suit] ?? '#0b0c0c', fontWeight: 'bold', textAlign: 'center' }}>
                  {SUIT_LABELS[draggedCard.suit] ?? draggedCard.suit[0]}
                </div>
                <div style={{ fontSize: '14px', fontWeight: 'bold', color: SUIT_COLOURS[draggedCard.suit] ?? '#0b0c0c', textAlign: 'right' }}>
                  {draggedCard.rankSymbol}
                </div>
              </div>
            </div>
          );
        })()}
      </main>
    </div>
  );
}
