/**
 * The card catalogue client.
 *
 * Every request is relative, never absolute. That is the whole point of the
 * single-origin topology in ADR-017: the browser talks to the same origin that
 * served the page, so there is no base URL to configure per environment and no
 * cross-origin handling anywhere in the system.
 */

/**
 * A STRIDE suit. Mirrors `org.maglez.eop.entity.StrideCategory` and the
 * `StrideCategory` schema in `docs/api/openapi.yml`.
 *
 * Declared as an `as const` array rather than a bare union of literals so that
 * the members survive compilation and `EnumMirrorParityTest` can compare them
 * with the other two artefacts. It was a bare union until EOP-105, and the
 * comment above it asserted parity with the server while nothing checked it —
 * the same unenforced invariant that let `PlayerDto.role` drift. `SUIT_LABELS`
 * below is keyed exhaustively off this union, so a drifted member would take the
 * label map with it while `tsc` stayed green.
 */
export const STRIDE_CATEGORIES = [
  "SPOOFING",
  "TAMPERING",
  "REPUDIATION",
  "INFORMATION_DISCLOSURE",
  "DENIAL_OF_SERVICE",
  "ELEVATION_OF_PRIVILEGE",
] as const;
export type StrideCategory = (typeof STRIDE_CATEGORIES)[number];

/** Narrows an unknown value to a {@link StrideCategory}. */
export function isStrideCategory(value: unknown): value is StrideCategory {
  return STRIDE_CATEGORIES.includes(value as StrideCategory);
}

/** One threat card, as published by `GET /api/v1/cards`. */
export interface Card {
  readonly cardId: string;
  readonly suit: StrideCategory;
  readonly rank: string;
  readonly rankSymbol: string;
  readonly rankValue: number;
  readonly threatPrompt: string;
}

/** A page of results, mirroring the server's paged envelope. */
export interface PagedResponse<T> {
  readonly content: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

/**
 * An error carrying the RFC 9457 problem detail the server returned, when it
 * returned one. The server never puts internal detail in a 5xx body, so `detail`
 * is deliberately treated as optional rather than assumed present.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ProblemDetail {
  readonly title?: string;
  readonly detail?: string;
}

/** Human-readable suit names, since the wire format is a shouting enum. */
export const SUIT_LABELS: Readonly<Record<StrideCategory, string>> = {
  SPOOFING: "Spoofing",
  TAMPERING: "Tampering",
  REPUDIATION: "Repudiation",
  INFORMATION_DISCLOSURE: "Information disclosure",
  DENIAL_OF_SERVICE: "Denial of service",
  ELEVATION_OF_PRIVILEGE: "Elevation of privilege",
};

async function problemMessage(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as ProblemDetail;
    return problem.detail ?? problem.title ?? response.statusText;
  } catch {
    // A body that is not JSON is not an error worth surfacing on its own; the
    // status code is the useful part.
    return response.statusText;
  }
}

/** Fetch one page of the card catalogue. */
export async function fetchCards(size = 20): Promise<PagedResponse<Card>> {
  const response = await fetch(`/api/v1/cards?size=${size}`, {
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as PagedResponse<Card>;
}

// Session API types

// The three enums below mirror server-side Java enums, and each one is declared
// as a runtime `as const` array with the union *derived* from it rather than as a
// bare union type. The reason is that a TypeScript union is erased at compile
// time: DTOs arrive through a type assertion in the fetch helpers below, never a
// parse, so a union alone cannot detect that the server sent a value the mirror
// does not list — every comparison against it just silently evaluates false.
// Keeping the members at runtime gives two tests something to check, and the
// split between them is deliberate. `api.test.ts` asserts each declared member is
// accepted and a non-member rejected, which is all a browser-side test can do:
// this project has no `@types/node` on purpose, so Vitest cannot read a file off
// disk. The cross-artefact comparison against the `enum` lists in
// `docs/api/openapi.yml` and the Java sources therefore lives in the Java suite,
// in `src/test/java/org/maglez/eop/docs/EnumMirrorParityTest.java`, which fails
// `./mvnw verify` when the three drift apart. See ADR-009 (EOP-105).

/** Mirrors `PlayerRole` (`org.maglez.eop.entity.PlayerRole`). */
export const PLAYER_ROLES = ['FACILITATOR', 'PARTICIPANT'] as const;
export type PlayerRole = (typeof PLAYER_ROLES)[number];

/** Mirrors `SessionStatus` (`org.maglez.eop.entity.SessionStatus`). */
export const SESSION_STATUSES = ['LOBBY', 'IN_PROGRESS', 'COMPLETED', 'ABANDONED'] as const;
export type SessionStatus = (typeof SESSION_STATUSES)[number];

/**
 * Mirrors the `ConnectionStatus` schema in `docs/api/openapi.yml`.
 *
 * Advisory only: the server discovers a dead connection on its next failed
 * write, so this can over-report `CONNECTED` between heartbeats. It is a display
 * hint and never an input to a game rule.
 */
export const CONNECTION_STATUSES = ['CONNECTED', 'DISCONNECTED'] as const;
export type ConnectionStatus = (typeof CONNECTION_STATUSES)[number];

export function isPlayerRole(value: unknown): value is PlayerRole {
  return PLAYER_ROLES.includes(value as PlayerRole);
}

export function isSessionStatus(value: unknown): value is SessionStatus {
  return SESSION_STATUSES.includes(value as SessionStatus);
}

export function isConnectionStatus(value: unknown): value is ConnectionStatus {
  return CONNECTION_STATUSES.includes(value as ConnectionStatus);
}

export interface PlayerDto {
  readonly playerId: string;
  readonly displayName: string;
  readonly seatOrder: number;
  readonly role: PlayerRole;
  readonly connectionStatus: ConnectionStatus;
}

export interface SessionStateDto {
  readonly sessionId: string;
  readonly joinCode: string;
  readonly status: SessionStatus;
  readonly players: readonly PlayerDto[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SessionAdmissionDto {
  readonly playerToken: string;
  readonly playerId: string;
  readonly session: SessionStateDto;
}

// Header name constant (matches backend)
export const PLAYER_TOKEN_HEADER = 'X-EoP-Player-Token';

/**
 * Create a new session
 */
export async function createSession(displayName: string): Promise<SessionAdmissionDto> {
  const response = await fetch('/api/v1/sessions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
    body: JSON.stringify({ displayName }),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as SessionAdmissionDto;
}

/**
 * Join an existing session
 */
export async function joinSession(joinCode: string, displayName: string): Promise<SessionAdmissionDto> {
  const response = await fetch(`/api/v1/sessions/${joinCode}/players`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
    body: JSON.stringify({ displayName }),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as SessionAdmissionDto;
}

/**
 * Get session state
 */
export async function getSession(sessionId: string, playerToken: string): Promise<SessionStateDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as SessionStateDto;
}

/**
 * Start the game (facilitator only)
 */
export async function startGame(sessionId: string, playerToken: string): Promise<SessionStateDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/start`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as SessionStateDto;
}

/**
 * Subscribe to session events via SSE.
 *
 * Uses `fetch` rather than `EventSource` because `EventSource` cannot set
 * custom request headers (ADR-015). The stream is used as a doorbell: each
 * `data:` frame signals that session state has changed; the caller is
 * responsible for re-fetching via `getSession`.
 *
 * @param sessionId  The session to subscribe to.
 * @param playerToken  The caller's credential (sent as `PLAYER_TOKEN_HEADER`).
 * @param onEvent  Called whenever a `data:` frame arrives.
 * @param onError  Called when the stream ends with a non-ok response or an
 *                 unexpected error. Receives the `ApiError` (for 4xx/5xx) or
 *                 a plain `Error` (for network failures).
 * @returns An `AbortController` whose `abort()` method tears down the stream.
 */
export function subscribeToSession(
  sessionId: string,
  playerToken: string,
  onEvent: (eventType: string | null) => void,
  onError: (err: ApiError | Error) => void,
): AbortController {
  const controller = new AbortController();

  const run = async (): Promise<void> => {
    const res = await fetch(`/api/v1/sessions/${sessionId}/events`, {
      headers: {
        'Accept': 'text/event-stream',
        [PLAYER_TOKEN_HEADER]: playerToken,
      },
      signal: controller.signal,
    });

    if (!res.ok) {
      // A non-2xx response (e.g. 403 expired token, 404 session gone) returns a
      // JSON problem-detail body, not an SSE stream.
      const message = await problemMessage(res);
      onError(new ApiError(res.status, message));
      return;
    }

    if (!res.body) return;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        // Keep the last (potentially incomplete) line in the buffer
        buffer = lines.pop() ?? '';

        let currentEventType: string | null = null;
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEventType = line.slice('event:'.length).trim();
          } else if (line.startsWith('data:')) {
            onEvent(currentEventType);
            currentEventType = null;
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
  };

  run().catch((err: unknown) => {
    // AbortError is expected on teardown — suppress it.
    if (err instanceof Error && err.name === 'AbortError') return;
    onError(err instanceof Error ? err : new Error(String(err)));
  });

  return controller;
}

// ---- Game Over API types ----

export interface LeaderboardRowDto {
  readonly playerId: string;
  readonly seatOrder: number;
  readonly displayName: string;
  readonly points: number;
  readonly position: number;
  readonly tied: boolean;
  readonly capturedBySuit: Readonly<Record<string, number>>;
}

export interface LeaderboardDto {
  readonly rows: readonly LeaderboardRowDto[];
  readonly sessionStatus: SessionStatus;
}

export async function getLeaderboard(sessionId: string, playerToken: string): Promise<LeaderboardDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/leaderboard`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as LeaderboardDto;
}

export async function startNewGame(sessionId: string, playerToken: string): Promise<void> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/new-game`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
}

// ---- Game Screen API types ----

export interface CardDto {
  readonly cardId: string;
  readonly suit: string;
  readonly rank: string;
  readonly rankSymbol: string;
  readonly rankValue: number;
  readonly threatPrompt: string;
}

export interface HandDto {
  readonly handId: string;
  readonly playerId: string;
  readonly cardCount: number;
  readonly cards: readonly CardDto[];
}

export interface TrickPlayDto {
  readonly trickPlayId: string;
  readonly playerId: string;
  readonly seatOrder: number;
  readonly card: CardDto;
  readonly threatLinked: boolean;
  readonly components: readonly string[];
  readonly notes?: string;
  readonly playedAt: string;
}

export interface TrickDto {
  readonly trickId: string;
  readonly sequence: number;
  readonly leaderSeat: number;
  readonly ledSuit?: string;
  readonly plays: readonly TrickPlayDto[];
  readonly winningSeat?: number;
}

export interface TrickStateDto {
  readonly trick?: TrickDto;
  readonly seatToPlay?: number;
  readonly complete: boolean;
  readonly nextLeaderSeat?: number;
  readonly handComplete: boolean;
}

export interface PlayCardRequest {
  readonly cardId: string;
  readonly threatLinked?: boolean;
  readonly components?: string[];
  readonly notes?: string;
}

/**
 * Deal cards to all players (facilitator only).
 *
 * Calls POST /api/v1/sessions/{id}/deal. The server returns 204 No Content on
 * success; the caller should then fetch the hand via fetchHand().
 */
export async function dealCards(sessionId: string, playerToken: string): Promise<void> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/deal`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
}

/** Fetch the current player's own hand */
export async function fetchHand(sessionId: string, playerToken: string): Promise<HandDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/hand`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as HandDto;
}

/** Get the current trick state */
export async function getTrickState(sessionId: string, playerToken: string): Promise<TrickStateDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/tricks/current`, {
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as TrickStateDto;
}

/** Play a card into the current trick */
export async function playCard(sessionId: string, playerToken: string, request: PlayCardRequest): Promise<TrickDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/plays`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as TrickDto;
}

/** Resolve the current trick */
export async function resolveTrick(sessionId: string, playerToken: string): Promise<TrickDto> {
  const response = await fetch(`/api/v1/sessions/${sessionId}/tricks/current/resolve`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      [PLAYER_TOKEN_HEADER]: playerToken,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }

  return (await response.json()) as TrickDto;
}
