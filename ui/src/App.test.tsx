import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import type { Card, PagedResponse } from "./api";

/**
 * The full deck is 78 cards, thirteen in each of the six suits.
 */
const DECK_SIZE = 78;

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
 * The last card in deck order, and the highest card in the game: the ace of the
 * trump suit. Aces are open threat cards, which is why the text invites the
 * player to name a threat rather than describing one.
 */
const TRUMP_ACE: Card = {
  cardId: "2a497b0e-e59d-50c9-a24b-f03f347dd4ed",
  suit: "ELEVATION_OF_PRIVILEGE",
  rank: "ACE",
  rankSymbol: "A",
  rankValue: 14,
  threatPrompt: "You've invented a new Elevation of Privilege attack",
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

  it("offers both calls to action but leaves them disabled when the feature flag is off", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    // Disabled because VITE_LOBBY_UI_ENABLED is unset in the test environment,
    // so the flag evaluates to false and the buttons are intentionally disabled.
    expect(screen.getByRole("button", { name: /create a session/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /join a session/i })).toBeDisabled();
  });

  it("credits Microsoft, because the licence obliges it", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(screen.getByText(/2009 Microsoft Corporation/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Creative Commons Attribution 3.0/i }),
    ).toHaveAttribute("href", "https://creativecommons.org/licenses/by/3.0/us/");
  });
});

describe("Card catalogue", () => {
  it("requests the catalogue on the same origin, with no base URL", async () => {
    respondWith(200, pageOf([SPOOFING_TWO]));

    render(<App />);

    await waitFor(() => {
      expect(fetch).toHaveBeenCalledWith(
        "/api/v1/cards?size=78",
        expect.objectContaining({ headers: { Accept: "application/json" } }),
      );
    });
  });

  it("renders each card with a readable suit name rather than the wire enum", async () => {
    respondWith(200, pageOf([SPOOFING_TWO, TRUMP_ACE]));

    render(<App />);

    expect(await screen.findByText("Spoofing")).toBeInTheDocument();
    expect(screen.getByText("Elevation of privilege")).toBeInTheDocument();
    expect(screen.getByText(SPOOFING_TWO.threatPrompt)).toBeInTheDocument();
    expect(screen.getByText("A")).toBeInTheDocument();
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

describe("Feature flag — lobby UI enabled", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it("enables Create and Join buttons when VITE_LOBBY_UI_ENABLED is true", async () => {
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(screen.getByRole("button", { name: /create a session/i })).not.toBeDisabled();
    expect(screen.getByRole("button", { name: /join a session/i })).not.toBeDisabled();
  });

  it("hides the 'not available yet' hint when the flag is on", async () => {
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(screen.queryByText(/not available yet/i)).not.toBeInTheDocument();
  });

  it("navigates to the Create form when Create button is clicked", async () => {
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");
    respondWith(200, pageOf([]));
    const user = userEvent.setup();

    await renderSettled();

    await user.click(screen.getByRole("button", { name: /create a session/i }));

    expect(screen.getByRole("heading", { name: /create a session/i })).toBeInTheDocument();
  });

  it("navigates to the Join form when Join button is clicked", async () => {
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");
    respondWith(200, pageOf([]));
    const user = userEvent.setup();

    await renderSettled();

    await user.click(screen.getByRole("button", { name: /join a session/i }));

    expect(screen.getByRole("heading", { name: /join a session/i })).toBeInTheDocument();
  });
});

describe("sessionStorage reconnect", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it("restores lobby view from valid stored session when flag is on", async () => {
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");

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
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");
    sessionStorage.setItem("eop_session", "not-valid-json{{{");
    respondWith(200, pageOf([]));

    await renderSettled();

    // Should show home screen, not lobby
    expect(screen.getByRole("heading", { name: /threat modelling card game/i })).toBeInTheDocument();
    // Storage should be cleared
    expect(sessionStorage.getItem("eop_session")).toBeNull();
  });

  it("falls back to home view when stored object is missing required fields", async () => {
    vi.stubEnv("VITE_LOBBY_UI_ENABLED", "true");
    // Missing playerToken
    sessionStorage.setItem("eop_session", JSON.stringify({ playerId: "p1", sessionId: "s1" }));
    respondWith(200, pageOf([]));

    await renderSettled();

    expect(screen.getByRole("heading", { name: /threat modelling card game/i })).toBeInTheDocument();
    expect(sessionStorage.getItem("eop_session")).toBeNull();
  });

  it("does not restore lobby when flag is off, even with valid stored session", async () => {
    // Flag is off (default in test env)
    const storedSession = {
      playerToken: "stored-token",
      playerId: "player-1",
      sessionId: "session-1",
    };
    sessionStorage.setItem("eop_session", JSON.stringify(storedSession));
    respondWith(200, pageOf([]));

    await renderSettled();

    // Should show home screen, not lobby
    expect(screen.getByRole("heading", { name: /threat modelling card game/i })).toBeInTheDocument();
  });
});
