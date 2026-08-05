import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import type { Card, PagedResponse } from "./api";

const SPOOFING_THREE: Card = {
  cardId: "11111111-1111-4111-8111-111111111111",
  suit: "SPOOFING",
  rank: "THREE",
  rankSymbol: "3",
  rankValue: 3,
  threatPrompt: "An attacker could pretend to be a legitimate user.",
};

const TRUMP_KING: Card = {
  cardId: "66666666-6666-4666-8666-666666666666",
  suit: "ELEVATION_OF_PRIVILEGE",
  rank: "KING",
  rankSymbol: "K",
  rankValue: 13,
  threatPrompt: "An attacker could gain permissions they were never granted.",
};

function pageOf(cards: readonly Card[]): PagedResponse<Card> {
  return {
    content: cards,
    page: 0,
    size: 6,
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

  it("offers both calls to action but leaves them disabled", async () => {
    respondWith(200, pageOf([]));

    await renderSettled();

    // Disabled on purpose: creating and joining a session is not built. A button
    // that looks live and does nothing is worse than one that admits it.
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
    respondWith(200, pageOf([SPOOFING_THREE]));

    render(<App />);

    await waitFor(() => {
      expect(fetch).toHaveBeenCalledWith(
        "/api/v1/cards?size=6",
        expect.objectContaining({ headers: { Accept: "application/json" } }),
      );
    });
  });

  it("renders each card with a readable suit name rather than the wire enum", async () => {
    respondWith(200, pageOf([SPOOFING_THREE, TRUMP_KING]));

    render(<App />);

    expect(await screen.findByText("Spoofing")).toBeInTheDocument();
    expect(screen.getByText("Elevation of privilege")).toBeInTheDocument();
    expect(screen.getByText(SPOOFING_THREE.threatPrompt)).toBeInTheDocument();
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
