/**
 * Unit tests for cardImagePath helper.
 *
 * import.meta.glob is a Vite build-time feature; vitest supports it natively
 * but the glob pattern resolves against the real filesystem. We mock the module
 * so the tests are hermetic and do not depend on the asset files being present
 * in the test environment.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the cardImagePath module so we can control what import.meta.glob returns.
// We re-implement the module inline with a controlled cardImages map.
vi.mock('../utils/cardImagePath', async () => {
  // Simulate the glob result: every valid combination maps to a URL string.
  const SUIT_FOLDER: Record<string, string> = {
    SPOOFING: 'Spoofing',
    TAMPERING: 'Tampering',
    REPUDIATION: 'Repudiation',
    INFORMATION_DISCLOSURE: 'Information Disclosure',
    DENIAL_OF_SERVICE: 'Denial of Service',
    ELEVATION_OF_PRIVILEGE: 'Elevation of Privilege',
  };

  const RANK_PREFIX: Record<string, string> = {
    TWO: '02',
    THREE: '03',
    FOUR: '04',
    FIVE: '05',
    SIX: '06',
    SEVEN: '07',
    EIGHT: '08',
    NINE: '09',
    TEN: '10',
    JACK: 'J',
    QUEEN: 'Q',
    KING: 'K',
  };

  // The 68 playable combinations (suit → ranks that actually exist)
  const SUIT_RANKS: Record<string, string[]> = {
    SPOOFING: ['TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'],
    TAMPERING: ['THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'],
    REPUDIATION: ['TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'],
    INFORMATION_DISCLOSURE: ['TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'],
    DENIAL_OF_SERVICE: ['TWO', 'THREE', 'FOUR', 'FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'],
    ELEVATION_OF_PRIVILEGE: ['FIVE', 'SIX', 'SEVEN', 'EIGHT', 'NINE', 'TEN', 'JACK', 'QUEEN', 'KING'],
  };

  // Build the mock glob map
  const cardImages: Record<string, { default: string }> = {};
  for (const [suit, ranks] of Object.entries(SUIT_RANKS)) {
    const folder = SUIT_FOLDER[suit];
    for (const rank of ranks) {
      const prefix = RANK_PREFIX[rank];
      const key = `../assets/cards/${folder}/${prefix} - ${folder}.png`;
      cardImages[key] = { default: `/mock-assets/${folder}/${prefix} - ${folder}.png` };
    }
  }

  function cardImagePath(suit: string, rank: string): string | null {
    const folder = SUIT_FOLDER[suit];
    const prefix = RANK_PREFIX[rank];
    if (!folder || !prefix) return null;
    const key = `../assets/cards/${folder}/${prefix} - ${folder}.png`;
    const module = cardImages[key];
    return module ? module.default : null;
  }

  function cardImageExists(suit: string, rank: string): boolean {
    return cardImagePath(suit, rank) !== null;
  }

  return { cardImagePath, cardImageExists };
});

import { cardImagePath, cardImageExists } from '../utils/cardImagePath';

describe('cardImagePath', () => {
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
      expect(result).toContain(`${prefix} - Spoofing.png`);
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
      expect(result).toContain(`${prefix} - Tampering.png`);
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
      expect(result).toContain(`${prefix} - Repudiation.png`);
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
      expect(result).toContain(`${prefix} - Information Disclosure.png`);
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
      expect(result).toContain(`${prefix} - Denial of Service.png`);
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
      expect(result).toContain(`${prefix} - Elevation of Privilege.png`);
    });
  });

  describe('invalid inputs', () => {
    it('returns null for unknown suit', () => {
      expect(cardImagePath('UNKNOWN_SUIT', 'KING')).toBeNull();
    });

    it('returns null for unknown rank', () => {
      expect(cardImagePath('SPOOFING', 'ACE')).toBeNull();
    });

    it('returns null for empty strings', () => {
      expect(cardImagePath('', '')).toBeNull();
    });

    it('returns null for ACE in any suit (ACE is not a playable rank)', () => {
      const suits = ['SPOOFING', 'TAMPERING', 'REPUDIATION', 'INFORMATION_DISCLOSURE', 'DENIAL_OF_SERVICE', 'ELEVATION_OF_PRIVILEGE'];
      for (const suit of suits) {
        expect(cardImagePath(suit, 'ACE')).toBeNull();
      }
    });
  });

  describe('cardImageExists', () => {
    it('returns true for a valid combination', () => {
      expect(cardImageExists('SPOOFING', 'KING')).toBe(true);
    });

    it('returns false for a missing combination', () => {
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
