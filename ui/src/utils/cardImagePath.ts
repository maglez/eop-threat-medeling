/**
 * Maps card suit and rank to the bundled PNG asset path.
 *
 * Suit enum values (uppercase) → folder name under ui/src/assets/cards/
 * Rank enum values (uppercase) → filename prefix
 *
 * Filename pattern: `{rankPrefix} - {SuitFolderName}.png`
 *
 * Not every rank exists in every suit (e.g. Tampering has no TWO,
 * Elevation of Privilege starts at FIVE). Returns null for missing combinations.
 *
 * The 68 card PNGs (~6.7 MB) are always bundled into dist/ regardless of the
 * VITE_CARD_IMAGES_ENABLED flag — Vite processes import.meta.glob at parse time
 * and cannot dead-code-eliminate it. The flag controls rendering only: when off,
 * cardImagePath() returns null and no <img> is rendered.
 */

/** Maps suit enum value → folder name on disk */
const SUIT_FOLDER: Record<string, string> = {
  SPOOFING: 'Spoofing',
  TAMPERING: 'Tampering',
  REPUDIATION: 'Repudiation',
  INFORMATION_DISCLOSURE: 'Information Disclosure',
  DENIAL_OF_SERVICE: 'Denial of Service',
  ELEVATION_OF_PRIVILEGE: 'Elevation of Privilege',
};

/** Maps rank enum value → filename prefix */
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

/**
 * Eagerly import all card PNGs so Vite can bundle them with content-hashed URLs.
 * These are always included in the bundle; the VITE_CARD_IMAGES_ENABLED flag
 * controls whether they are rendered, not whether they are bundled.
 */
const cardImages: Record<string, { default: string }> =
  import.meta.glob<{ default: string }>('../assets/cards/**/*.png', { eager: true });

/**
 * Returns the bundled URL for a card image, or `null` if:
 * - the VITE_CARD_IMAGES_ENABLED flag is off, or
 * - the combination does not exist (e.g. TWO of Tampering, or any ACE).
 *
 * @param suit  - Suit enum string, e.g. `"SPOOFING"`
 * @param rank  - Rank enum string, e.g. `"JACK"`
 */
export function cardImagePath(suit: string, rank: string): string | null {
  if (import.meta.env.VITE_CARD_IMAGES_ENABLED !== 'true') {
    return null;
  }

  const folder = SUIT_FOLDER[suit];
  const prefix = RANK_PREFIX[rank];

  if (!folder || !prefix) {
    return null;
  }

  const key = `../assets/cards/${folder}/${prefix} - ${folder}.png`;
  const module = cardImages[key];
  return module ? module.default : null;
}

/**
 * Returns true when a real PNG asset exists for the given suit/rank pair
 * and the card-images flag is on.
 */
export function cardImageExists(suit: string, rank: string): boolean {
  return cardImagePath(suit, rank) !== null;
}
