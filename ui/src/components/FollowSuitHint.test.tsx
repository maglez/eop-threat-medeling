import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FollowSuitHint } from './FollowSuitHint';

/**
 * Unit tests for the proactive follow-suit hint.
 *
 * These exercise the component's own gate through its `enabled` prop. The
 * three positions of the VITE_FOLLOW_SUIT_HINT_ENABLED variable itself are
 * exercised in GameScreen.test.tsx, which is where `import.meta.env` is read
 * and therefore the only place `vi.stubEnv` can reach it (ADR-037).
 */
describe('FollowSuitHint', () => {
  const enabledOnMyTurn = {
    enabled: true,
    isMyTurn: true,
    canFollowLedSuit: true,
  } as const;

  describe('when the hint applies', () => {
    it('names the suit the player must follow', () => {
      render(<FollowSuitHint {...enabledOnMyTurn} ledSuit="TAMPERING" />);

      expect(screen.getByText(/you must play a tampering card/i)).toBeInTheDocument();
    });

    it('renders a multi-word suit lowercased with underscores replaced by spaces', () => {
      render(<FollowSuitHint {...enabledOnMyTurn} ledSuit="DENIAL_OF_SERVICE" />);

      // Case-sensitive on purpose. A /.../i matcher passes just as well against
      // toUpperCase(), so the case half of the transform would go untested.
      expect(screen.getByRole('status')).toHaveTextContent(
        'Follow suit: you must play a denial of service card.',
      );
    });

    it('carries the GOV.UK hint class and the eop- styling hook', () => {
      render(<FollowSuitHint {...enabledOnMyTurn} ledSuit="SPOOFING" />);

      // Losing either class is a silent visual regression no text assertion sees.
      expect(screen.getByRole('status')).toHaveClass('govuk-hint', 'eop-follow-suit-hint');
    });

    it('announces itself politely to assistive technology', () => {
      render(<FollowSuitHint {...enabledOnMyTurn} ledSuit="SPOOFING" />);

      expect(screen.getByRole('status')).toHaveTextContent(/follow suit/i);
    });
  });

  describe('when the hint stops applying', () => {
    it('disappears once the next trick opens with no suit led yet', () => {
      const { rerender, container } = render(
        <FollowSuitHint {...enabledOnMyTurn} ledSuit="TAMPERING" />,
      );
      expect(screen.getByRole('status')).toBeInTheDocument();

      // The trick was won and a fresh one opened: the server omits ledSuit again.
      rerender(<FollowSuitHint {...enabledOnMyTurn} />);

      expect(container).toBeEmptyDOMElement();
    });

    it('disappears once the turn passes to another player', () => {
      const { rerender, container } = render(
        <FollowSuitHint {...enabledOnMyTurn} ledSuit="TAMPERING" />,
      );
      expect(screen.getByRole('status')).toBeInTheDocument();

      rerender(<FollowSuitHint {...enabledOnMyTurn} isMyTurn={false} ledSuit="TAMPERING" />);

      expect(container).toBeEmptyDOMElement();
    });
  });

  describe('when the hint does not apply', () => {
    it('renders nothing while another player is to play', () => {
      const { container } = render(
        <FollowSuitHint {...enabledOnMyTurn} isMyTurn={false} ledSuit="TAMPERING" />,
      );

      expect(container).toBeEmptyDOMElement();
    });

    it('renders nothing when no suit has been led, so this player is leading', () => {
      const { container } = render(<FollowSuitHint {...enabledOnMyTurn} />);

      expect(container).toBeEmptyDOMElement();
    });

    it('renders nothing when the hand holds no card of the led suit', () => {
      const { container } = render(
        <FollowSuitHint {...enabledOnMyTurn} canFollowLedSuit={false} ledSuit="TAMPERING" />,
      );

      expect(container).toBeEmptyDOMElement();
    });

    it('renders nothing when the feature is disabled', () => {
      const { container } = render(
        <FollowSuitHint {...enabledOnMyTurn} enabled={false} ledSuit="TAMPERING" />,
      );

      expect(container).toBeEmptyDOMElement();
    });
  });
});
