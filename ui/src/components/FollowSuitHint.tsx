import React from 'react';
import type { StrideCategory } from '../api';

interface FollowSuitHintProps {
  /**
   * Position of the VITE_FOLLOW_SUIT_HINT_ENABLED build-time flag, threaded down
   * from the component that reads `import.meta.env` (ADR-037).
   */
  readonly enabled: boolean;
  /** Whether it is this player's turn to play a card. */
  readonly isMyTurn: boolean;
  /**
   * Suit led on the current trick. Absent until the first card of a trick is
   * played, which is also the case in which this player is the one leading.
   */
  readonly ledSuit?: StrideCategory | undefined;
  /**
   * Whether this player's hand holds at least one card of the led suit. The
   * domain rule is follow-suit-if-able — see {@code Trick#play}, which throws
   * MustFollowSuitException only when the hand holds the led suit — so a hint
   * shown to a player holding none of it would state a falsehood.
   */
  readonly canFollowLedSuit: boolean;
}

/**
 * Proactive hint telling the active player which suit they must follow.
 *
 * Purely presentational. The rule itself is enforced server-side and a play
 * that breaks it is rejected with 422 regardless of this hint or its flag; the
 * hint only spares the player from discovering the constraint by rejection.
 *
 * The feature flag is evaluated here rather than at the call site so that the
 * gate cannot be bypassed by a future caller (ADR-037). Renders nothing at all
 * when the hint does not apply — never a partial or placeholder message.
 */
export function FollowSuitHint({
  enabled,
  isMyTurn,
  ledSuit,
  canFollowLedSuit,
}: FollowSuitHintProps): React.JSX.Element | null {
  if (!enabled || !isMyTurn || ledSuit === undefined || !canFollowLedSuit) {
    return null;
  }

  return (
    <p className="govuk-hint eop-follow-suit-hint" role="status">
      Follow suit: you must play a {ledSuit.toLowerCase().replace(/_/g, ' ')} card.
    </p>
  );
}
