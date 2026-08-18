import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import type { Card, PagedResponse } from "./api";

/**
 * The full deck is 68 cards: Tampering starts at rank 3 (eleven ranks) and
 * Elevation of Privilege starts at rank 5 (nine ranks); the other four suits
 * hold twelve ranks (2–K) each. King is the highest card. See ADR-041.
 */
const DECK_SIZE = 68;

/** The first card in deck order: Spoofing, rank two. */
const SPOOFING_TWO: Card = {
  cardId: "5e42f424-e084-5b0c-a428-7785d15f5dd8",
  suit: "SPOOFING",
  rank: "TWO",
  rankSymbol: "2",
  rankValue: 2,
  threatPrompt:
    "An attacker could squat on the random port or socket that the server normally uses",
};

/**
 * The last card in deck order, and the highest card in the game: the King of the
 * trump suit (Elevation of Privilege). King is the highest rank (13) since the
 * physical printed deck has no Ace cards. See ADR-041.
 */
const TRUMP_KING: Card = {
  cardId: "f4ea3e6e-5cd5-53d0-a32d-b9f389069b74",
  suit: "ELEVATION_OF_PRIVILEGE",
  rank: "KING",
  rankSymbol: "K",
  rankValue: 13,
  threatPrompt: "An attacker can inject a command that the system will run at a higher privilege level",
};

function pageOf(cards: readonly Card[]): PagedResponse<Card> {
  return {
    content: cards,
    page: 0,
    size: DECK_SIZE,
    totalElements: cards.length,
    totalPages: 1,
  };
}

function respondWith(status: number, body: unknown): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(() =>
      Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        statusText: `status ${status}`,
        json: () => Promise.resolve(body),
      } as Response),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * Render and wait for the catalogue request to settle.
 *
 * Every render fires a request, so a synchronous test finishes before the state
 * update lands and React warns that it happened outside `act`. Waiting for the
 * settled state is the fix; suppressing the warning would only hide the next one.
 */
async function renderSettled(): Promise<void> {
  render(<App />);
  await screen.findByText(/placeholder deck|could not be loaded/i);
}

describe("App shell", () => {
  it("names the application in a top level heading", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(
      screen.getByRole("heading", { level: 1, name: /threat modelling card game/i }),
    ).toBeInTheDocument();
  });

  it("credits Microsoft, because the licence obliges it", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(screen.getByText(/2009 Microsoft Corporation/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Creative Commons Attribution 3.0/i }),
    ).toHaveAttribute("href", "https://creativecommons.org/licenses/by/3.0/us/");
  });

  it("shows Create and Join buttons as enabled (lobby UI always on)", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    const createButton = screen.getByRole("button", { name: /create a session/i });
    const joinButton = screen.getByRole("button", { name: /join a session/i });
    expect(createButton).not.toBeDisabled();
    expect(joinButton).not.toBeDisabled();
    expect(createButton).not.toHaveAttribute("aria-disabled", "true");
    expect(joinButton).not.toHaveAttribute("aria-disabled", "true");
  });

  it("navigates to the Create form when Create button is clicked", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /create a session/i }));

    expect(screen.getByRole("heading", { name: /create a session/i })).toBeInTheDocument();
  });

  it("navigates to the Join form when Join button is clicked", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /join a session/i }));

    expect(screen.getByRole("heading", { name: /join a session/i })).toBeInTheDocument();
  });
});

describe("Card catalogue", () => {
  it("requests the catalogue on the same origin, with no base URL", async () => {
    respondWith(200, pageOf([SPOOFING_TWO]));

    render(<App />);

    await waitFor(() => {
      expect(fetch).toHaveBeenCalledWith(
        `/api/v1/cards?size=${DECK_SIZE}`,
        expect.objectContaining({ headers: { Accept: "application/json" } }),
      );
    });
  });

  it("renders each card with a readable suit name rather than the wire enum", async () => {
    respondWith(200, pageOf([SPOOFING_TWO, TRUMP_KING]));

    render(<App />);

    expect(await screen.findByText("Spoofing")).toBeInTheDocument();
    expect(screen.getByText("Elevation of privilege")).toBeInTheDocument();
    expect(screen.getByText(SPOOFING_TWO.threatPrompt)).toBeInTheDocument();
    expect(screen.getByText("K")).toBeInTheDocument();
  });

  it("surfaces the problem detail when the server rejects the request", async () => {
    respondWith(400, { title: "Invalid request", detail: "size must be at most 100" });

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "size must be at most 100",
    );
  });

  it("falls back to the status text when the error body carries no detail", async () => {
    respondWith(500, {});

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("status 500");
  });
});

describe("Feature flag — game screen enabled", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it("does not navigate to game screen when VITE_GAME_SCREEN_ENABLED is off", async () => {
    // Flag is off — even if the session transitions to IN_PROGRESS,
    // the lobby should stay on screen (showing the "game has started" warning).
    // Explicitly disable the game screen flag — .env.local may set it to true on developer machines
    vi.stubEnv("VITE_GAME_SCREEN_ENABLED", "false");

    const inProgressSession = {
      sessionId: "session-1",
      joinCode: "XYZ999",
      status: "IN_PROGRESS",
      players: [
        { playerId: "player-1", displayName: "Alice", seatOrder: 0, role: "FACILITATOR", connectionStatus: "CONNECTED" },
        { playerId: "player-2", displayName: "Bob", seatOrder: 1, role: "PLAYER", connectionStatus: "CONNECTED" },
        { playerId: "player-3", displayName: "Carol", seatOrder: 2, role: "PLAYER", connectionStatus: "CONNECTED" },
      ],
      createdAt: "2023-01-01T00:00:00Z",
      updatedAt: "2023-01-01T00:00:00Z",
    };

    const storedSession = { playerToken: "tok-1", playerId: "player-1", sessionId: "session-1" };
    sessionStorage.setItem("eop_session", JSON.stringify(storedSession));

    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.includes("/events")) return new Promise(() => {});
      return Promise.resolve({
        ok: true, status: 200,
        json: () => Promise.resolve(inProgressSession),
      } as Response);
    }));

    render(<App />);

    // Lobby should show the "game has started" warning, not the game screen
    await waitFor(() => {
      expect(screen.getByText("The game has started")).toBeInTheDocument();
    });
    // Game screen hand group should NOT be present
    expect(screen.queryByRole("group", { name: "Your hand" })).not.toBeInTheDocument();
  });

  it("navigates to game screen when VITE_GAME_SCREEN_ENABLED is on and session is IN_PROGRESS", async () => {
    vi.stubEnv("VITE_GAME_SCREEN_ENABLED", "true");

    const inProgressSession = {
      sessionId: "session-1",
      joinCode: "XYZ999",
      status: "IN_PROGRESS",
      players: [
        { playerId: "player-1", displayName: "Alice", seatOrder: 0, role: "FACILITATOR", connectionStatus: "CONNECTED" },
        { playerId: "player-2", displayName: "Bob", seatOrder: 1, role: "PLAYER", connectionStatus: "CONNECTED" },
        { playerId: "player-3", displayName: "Carol", seatOrder: 2, role: "PLAYER", connectionStatus: "CONNECTED" },
      ],
      createdAt: "2023-01-01T00:00:00Z",
      updatedAt: "2023-01-01T00:00:00Z",
    };

    const storedSession = { playerToken: "tok-1", playerId: "player-1", sessionId: "session-1" };
    sessionStorage.setItem("eop_session", JSON.stringify(storedSession));

    // GameScreen will call fetchHand, getTrickState, getSession, and subscribeToSession.
    // Stub them all so the component can mount without errors.
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.includes("/events")) return new Promise(() => {});
      if (url.includes("/hand")) {
        return Promise.resolve({
          ok: true, status: 200,
          json: () => Promise.resolve({ handId: "h1", playerId: "player-1", cardCount: 0, cards: [] }),
        } as Response);
      }
      if (url.includes("/tricks/current")) {
        return Promise.resolve({
          ok: true, status: 200,
          json: () => Promise.resolve({ trick: null, seatToPlay: null, complete: false, nextLeaderSeat: null, handComplete: false }),
        } as Response);
      }
      // Default: session fetch
      return Promise.resolve({
        ok: true, status: 200,
        json: () => Promise.resolve(inProgressSession),
      } as Response);
    }));

    render(<App />);

    // The lobby fires onGameStarted when it sees IN_PROGRESS, which (with flag ON)
    // switches to the game screen. The game screen renders the hand group.
    await waitFor(() => {
      expect(screen.getByRole("group", { name: "Your hand" })).toBeInTheDocument();
    });
  });
});

describe("sessionStorage reconnect", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it("restores lobby view from valid stored session", async () => {
    const storedSession = {
      playerToken: "stored-token",
      playerId: "player-1",
      sessionId: "session-1",
    };
    sessionStorage.setItem("eop_session", JSON.stringify(storedSession));

    // Stub fetch so LobbyScreen can load the session
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.includes("/events")) return new Promise(() => {});
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          sessionId: "session-1",
          joinCode: "XYZ999",
          status: "LOBBY",
          players: [
            { playerId: "player-1", displayName: "Alice", seatOrder: 0, role: "FACILITATOR", connectionStatus: "CONNECTED" }
          ],
          createdAt: "2023-01-01T00:00:00Z",
          updatedAt: "2023-01-01T00:00:00Z",
        }),
      } as Response);
    }));

    render(<App />);

    // Should land directly in the lobby, not the home screen
    await waitFor(() => {
      expect(screen.getByText("Game Lobby")).toBeInTheDocument();
    });
  });

  it("falls back to home view when stored JSON is invalid", async () => {
    sessionStorage.setItem("eop_session", "not-valid-json{{{");
    respondWith(200, pageOf([]));

    await renderSettled();

    // Should show home screen, not lobby
    expect(screen.getByRole("heading", { name: /threat modelling card game/i })).toBeInTheDocument();
    // Storage should be cleared
    expect(sessionStorage.getItem("eop_session")).toBeNull();
  });

  it("falls back to home view when stored object is missing required fields", async () => {
    // Missing playerToken
    sessionStorage.setItem("eop_session", JSON.stringify({ playerId: "p1", sessionId: "s1" }));
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(screen.getByRole("heading", { name: /threat modelling card game/i })).toBeInTheDocument();
    expect(sessionStorage.getItem("eop_session")).toBeNull();
  });
});
