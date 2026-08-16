/**
 * The card catalogue client.
 *
 * Every request is relative, never absolute. That is the whole point of the
 * single-origin topology in ADR-017: the browser talks to the same origin that
 * served the page, so there is no base URL to configure per environment and no
 * cross-origin handling anywhere in the system.
 */

/** A STRIDE suit, matching the server's enum exactly. */
export type StrideCategory =
  | "SPOOFING"
  | "TAMPERING"
  | "REPUDIATION"
  | "INFORMATION_DISCLOSURE"
  | "DENIAL_OF_SERVICE"
  | "ELEVATION_OF_PRIVILEGE";

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
export interface PlayerDto {
  readonly playerId: string;
  readonly displayName: string;
  readonly seatOrder: number;
  readonly role: 'FACILITATOR' | 'PLAYER';
  readonly connectionStatus: string;
}

export interface SessionStateDto {
  readonly sessionId: string;
  readonly joinCode: string;
  readonly status: 'LOBBY' | 'IN_PROGRESS' | 'ENDED';
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
  onEvent: () => void,
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

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value, { stream: true });
        if (text.includes('data:')) {
          onEvent();
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
