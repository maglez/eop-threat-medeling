/**
 * Unit tests for cardImagePath helper.
 *
 * Vitest supports import.meta.glob natively and resolves the glob pattern
 * against the real filesystem. The 68 PNG assets are committed to the repo
 * under ui/src/assets/cards/.
 *
 * The 68 PNGs are always bundled (Vite processes import.meta.glob at parse
 * time). The VITE_CARD_IMAGES_ENABLED flag is checked inside cardImagePath()
 * at call time — when off, the function returns null regardless of the map.
 *
 * Flag-OFF tests use the static import (no stub needed — the flag is unset
 * in the test environment so cardImagePath returns null for everything).
 * Flag-ON tests stub the env var; because the flag is read at call time
 * (not at module load time), vi.stubEnv alone is sufficient — no
 * vi.resetModules() is needed.
 */

import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';

// ─── Flag-OFF tests (no stub needed — flag is unset, cardImagePath returns null) ─

import { cardImagePath as cardImagePathOff, cardImageExists as cardImageExistsOff } from '../utils/cardImagePath';

describe('cardImagePath — flag OFF (VITE_CARD_IMAGES_ENABLED unset)', () => {
  describe('invalid inputs always return null', () => {
    it('returns null for unknown suit', () => {
      expect(cardImagePathOff('UNKNOWN_SUIT', 'KING')).toBeNull();
    });

    it('returns null for unknown rank', () => {
      expect(cardImagePathOff('SPOOFING', 'ACE')).toBeNull();
    });

    it('returns null for empty strings', () => {
      expect(cardImagePathOff('', '')).toBeNull();
    });

    it('returns null for ACE in any suit (ACE is not a playable rank)', () => {
      const suits = ['SPOOFING', 'TAMPERING', 'REPUDIATION', 'INFORMATION_DISCLOSURE', 'DENIAL_OF_SERVICE', 'ELEVATION_OF_PRIVILEGE'];
      for (const suit of suits) {
        expect(cardImagePathOff(suit, 'ACE')).toBeNull();
      }
    });
  });

  describe('valid combinations return null when flag is off', () => {
    it('returns null for SPOOFING/KING when flag is off', () => {
      expect(cardImagePathOff('SPOOFING', 'KING')).toBeNull();
    });

    it('cardImageExists returns false when flag is off', () => {
      expect(cardImageExistsOff('SPOOFING', 'KING')).toBe(false);
    });
  });
});

// ─── Flag-ON tests (stub env var — flag is checked at call time, no resetModules needed) ──

describe('cardImagePath — flag ON (VITE_CARD_IMAGES_ENABLED=true)', () => {
  let cardImagePath: (suit: string, rank: string) => string | null;
  let cardImageExists: (suit: string, rank: string) => boolean;

  beforeAll(async () => {
    vi.stubEnv('VITE_CARD_IMAGES_ENABLED', 'true');
    vi.resetModules();
    const mod = await import('../utils/cardImagePath');
    cardImagePath = mod.cardImagePath;
    cardImageExists = mod.cardImageExists;
  });

  afterAll(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  describe('Spoofing suit (12 cards: TWO–KING)', () => {
    it.each([
      ['TWO', '02'],
      ['THREE', '03'],
      ['FOUR', '04'],
      ['FIVE', '05'],
      ['SIX', '06'],
      ['SEVEN', '07'],
      ['EIGHT', '08'],
      ['NINE', '09'],
      ['TEN', '10'],
      ['JACK', 'J'],
      ['QUEEN', 'Q'],
      ['KING', 'K'],
    ])('returns a path for rank %s (prefix %s)', (rank, prefix) => {
      const result = cardImagePath('SPOOFING', rank);
      expect(result).not.toBeNull();
      expect(decodeURIComponent(result!)).toContain(`${prefix} - Spoofing`);
    });
  });

  describe('Tampering suit (11 cards: THREE–KING, no TWO)', () => {
    it('returns null for TWO of Tampering (card does not exist)', () => {
      expect(cardImagePath('TAMPERING', 'TWO')).toBeNull();
    });

    it.each([
      ['THREE', '03'],
      ['FOUR', '04'],
      ['FIVE', '05'],
      ['SIX', '06'],
      ['SEVEN', '07'],
      ['EIGHT', '08'],
      ['NINE', '09'],
      ['TEN', '10'],
      ['JACK', 'J'],
      ['QUEEN', 'Q'],
      ['KING', 'K'],
    ])('returns a path for rank %s (prefix %s)', (rank, prefix) => {
      const result = cardImagePath('TAMPERING', rank);
      expect(result).not.toBeNull();
      expect(decodeURIComponent(result!)).toContain(`${prefix} - Tampering`);
    });
  });

  describe('Repudiation suit (12 cards: TWO–KING)', () => {
    it.each([
      ['TWO', '02'], ['THREE', '03'], ['FOUR', '04'], ['FIVE', '05'],
      ['SIX', '06'], ['SEVEN', '07'], ['EIGHT', '08'], ['NINE', '09'],
      ['TEN', '10'], ['JACK', 'J'], ['QUEEN', 'Q'], ['KING', 'K'],
    ])('returns a path for rank %s', (rank, prefix) => {
      const result = cardImagePath('REPUDIATION', rank);
      expect(result).not.toBeNull();
      expect(decodeURIComponent(result!)).toContain(`${prefix} - Repudiation`);
    });
  });

  describe('Information Disclosure suit (12 cards: TWO–KING)', () => {
    it.each([
      ['TWO', '02'], ['THREE', '03'], ['FOUR', '04'], ['FIVE', '05'],
      ['SIX', '06'], ['SEVEN', '07'], ['EIGHT', '08'], ['NINE', '09'],
      ['TEN', '10'], ['JACK', 'J'], ['QUEEN', 'Q'], ['KING', 'K'],
    ])('returns a path for rank %s', (rank, prefix) => {
      const result = cardImagePath('INFORMATION_DISCLOSURE', rank);
      expect(result).not.toBeNull();
      expect(decodeURIComponent(result!)).toContain(`${prefix} - Information Disclosure`);
    });
  });

  describe('Denial of Service suit (12 cards: TWO–KING)', () => {
    it.each([
      ['TWO', '02'], ['THREE', '03'], ['FOUR', '04'], ['FIVE', '05'],
      ['SIX', '06'], ['SEVEN', '07'], ['EIGHT', '08'], ['NINE', '09'],
      ['TEN', '10'], ['JACK', 'J'], ['QUEEN', 'Q'], ['KING', 'K'],
    ])('returns a path for rank %s', (rank, prefix) => {
      const result = cardImagePath('DENIAL_OF_SERVICE', rank);
      expect(result).not.toBeNull();
      expect(decodeURIComponent(result!)).toContain(`${prefix} - Denial of Service`);
    });
  });

  describe('Elevation of Privilege suit (9 cards: FIVE–KING)', () => {
    it.each(['TWO', 'THREE', 'FOUR'])('returns null for rank %s (card does not exist)', (rank) => {
      expect(cardImagePath('ELEVATION_OF_PRIVILEGE', rank)).toBeNull();
    });

    it.each([
      ['FIVE', '05'], ['SIX', '06'], ['SEVEN', '07'], ['EIGHT', '08'],
      ['NINE', '09'], ['TEN', '10'], ['JACK', 'J'], ['QUEEN', 'Q'], ['KING', 'K'],
    ])('returns a path for rank %s', (rank, prefix) => {
      const result = cardImagePath('ELEVATION_OF_PRIVILEGE', rank);
      expect(result).not.toBeNull();
      expect(decodeURIComponent(result!)).toContain(`${prefix} - Elevation of Privilege`);
    });
  });

  describe('cardImageExists', () => {
    it('returns true for a valid combination', () => {
      expect(cardImageExists('SPOOFING', 'KING')).toBe(true);
    });

    it('returns false for a missing combination (Tampering has no TWO)', () => {
      expect(cardImageExists('TAMPERING', 'TWO')).toBe(false);
    });

    it('returns false for an unknown suit', () => {
      expect(cardImageExists('UNKNOWN', 'KING')).toBe(false);
    });
  });

  describe('total playable card count', () => {
    const ALL_SUITS = ['SPOOFING', 'TAMPERING', 'REPUDIATION', 'INFORMATION_DISCLOSURE', 'DENIAL_OF_SERVICE', 'ELEVATION_OF_PRIVILEGE'];
    const ALL_RANKS = ['TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'];

    it('finds exactly 68 playable cards across all suits', () => {
      let count = 0;
      for (const suit of ALL_SUITS) {
        for (const rank of ALL_RANKS) {
          if (cardImageExists(suit, rank)) count++;
        }
      }
      expect(count).toBe(68);
    });
  });
});
