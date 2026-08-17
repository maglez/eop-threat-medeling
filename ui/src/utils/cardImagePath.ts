/**
 * Maps card suit and rank to the bundled PNG asset path.
 *
 * Suit enum values (uppercase) → folder name under ui/src/assets/cards/
 * Rank enum values (uppercase) → filename prefix
 *
 * Filename pattern: `{rankPrefix} - {SuitFolderName}.png`
 *
 * Not every rank exists in every suit (e.g. Tampering has no TWO,
 * Elevation of Privilege starts at FIVE). Callers should guard with
 * `cardImageExists(suit, rank)` before rendering an <img>.
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
 * Eagerly import all card PNGs so Vite can bundle them.
 * The key is `{rankPrefix} - {SuitFolderName}` (without extension).
 */
const cardImages = import.meta.glob<{ default: string }>(
  '../assets/cards/**/*.png',
  { eager: true },
);

/**
 * Returns the bundled URL for a card image, or `null` if the combination
 * does not exist (e.g. TWO of Tampering, or any ACE).
 *
 * @param suit  - Suit enum string, e.g. `"SPOOFING"`
 * @param rank  - Rank enum string, e.g. `"JACK"`
 */
export function cardImagePath(suit: string, rank: string): string | null {
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
 * Returns true when a real PNG asset exists for the given suit/rank pair.
 */
export function cardImageExists(suit: string, rank: string): boolean {
  return cardImagePath(suit, rank) !== null;
}
