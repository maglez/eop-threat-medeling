import { useEffect, useState } from "react";
import { type Card, SUIT_LABELS, fetchCards } from "../api";

type CatalogueState =
  | { readonly status: "loading" }
  | { readonly status: "loaded"; readonly cards: readonly Card[] }
  | { readonly status: "failed"; readonly message: string };

/**
 * The card catalogue panel.
 *
 * This exists to prove the proxy end to end rather than to be a feature: a real
 * request over the same origin, through Caddy, into the application, out of
 * PostgreSQL and onto the page. Rendering static text would have proved only that
 * Vite works.
 */
/**
 * The full deck is 78 cards - thirteen ranks in each of the six STRIDE suits.
 * The catalogue asks for all of them in one request rather than paginating,
 * because a reference list of the deck is only useful whole. 78 is inside the
 * server's maximum page size of 100, so this is a single round trip.
 */
const DECK_SIZE = 78;

export function CardCatalogue(): React.JSX.Element {
  const [state, setState] = useState<CatalogueState>({ status: "loading" });

  useEffect(() => {
    let abandoned = false;

    fetchCards(DECK_SIZE)
      .then((page) => {
        if (!abandoned) {
          setState({ status: "loaded", cards: page.content });
        }
      })
      .catch((error: unknown) => {
        if (!abandoned) {
          const message =
            error instanceof Error ? error.message : "The request failed.";
          setState({ status: "failed", message });
        }
      });

    // React runs effects twice in development. Without this the second run's
    // response could overwrite the first after unmount.
    return () => {
      abandoned = true;
    };
  }, []);

  if (state.status === "loading") {
    return <p className="govuk-body">Loading the deck...</p>;
  }

  if (state.status === "failed") {
    return (
      <div
        className="govuk-error-summary"
        data-module="govuk-error-summary"
        role="alert"
      >
        <h2 className="govuk-error-summary__title">
          The deck could not be loaded
        </h2>
        <div className="govuk-error-summary__body">
          <p className="govuk-body">{state.message}</p>
        </div>
      </div>
    );
  }

  return (
    <table className="govuk-table">
      <caption className="govuk-table__caption govuk-table__caption--m">
        Placeholder deck
      </caption>
      <thead className="govuk-table__head">
        <tr className="govuk-table__row">
          <th scope="col" className="govuk-table__header">
            Suit
          </th>
          <th scope="col" className="govuk-table__header">
            Rank
          </th>
          <th scope="col" className="govuk-table__header">
            Threat
          </th>
        </tr>
      </thead>
      <tbody className="govuk-table__body">
        {state.cards.map((card) => (
          <tr className="govuk-table__row" key={card.cardId}>
            <td className="govuk-table__cell">{SUIT_LABELS[card.suit]}</td>
            <td className="govuk-table__cell">{card.rankSymbol}</td>
            <td className="govuk-table__cell">{card.threatPrompt}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}