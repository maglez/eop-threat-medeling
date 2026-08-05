import { useEffect, useState } from "react";
import { type Card, SUIT_LABELS, fetchCards } from "./api";

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
function Catalogue(): React.JSX.Element {
  const [state, setState] = useState<CatalogueState>({ status: "loading" });

  useEffect(() => {
    let abandoned = false;

    fetchCards(6)
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

/** The application shell. No game behaviour lives here yet, deliberately. */
export default function App(): React.JSX.Element {
  return (
    <>
      <header className="govuk-header" data-module="govuk-header">
        <div className="govuk-header__container govuk-width-container">
          <div className="govuk-header__content">
            <span className="govuk-header__service-name">
              Elevation of Privilege
            </span>
          </div>
        </div>
      </header>

      <div className="govuk-width-container">
        <main className="govuk-main-wrapper" id="main-content">
          <h1 className="govuk-heading-xl">Threat modelling card game</h1>

          <p className="govuk-body-l">
            A digital version of the Elevation of Privilege card game, for teams
            who cannot share a table.
          </p>

          <div className="govuk-button-group">
            {/*
              Both buttons are deliberately inert. Creating and joining a session
              is EOP-10 and EOP-11; wiring them now would mean shipping a button
              that lies about what it does.
            */}
            <button
              type="button"
              className="govuk-button"
              data-module="govuk-button"
              disabled
              aria-disabled="true"
            >
              Create a session
            </button>
            <button
              type="button"
              className="govuk-button govuk-button--secondary"
              data-module="govuk-button"
              disabled
              aria-disabled="true"
            >
              Join a session
            </button>
          </div>

          <p className="govuk-hint">
            Creating and joining a session is not built yet.
          </p>

          <h2 className="govuk-heading-l">The deck</h2>
          <Catalogue />
        </main>
      </div>

      <footer className="govuk-footer">
        <div className="govuk-width-container">
          <div className="govuk-footer__meta">
            <div className="govuk-footer__meta-item govuk-footer__meta-item--grow">
              <p className="govuk-footer__licence-description">
                Elevation of Privilege is{" "}
                <span className="govuk-!-font-weight-bold">
                  &copy; 2009 Microsoft Corporation
                </span>
                , licensed under{" "}
                <a
                  className="govuk-footer__link"
                  href="https://creativecommons.org/licenses/by/3.0/us/"
                >
                  Creative Commons Attribution 3.0 United States
                </a>
                . The cards shown above are placeholders written for this project,
                not Microsoft&apos;s.
              </p>
            </div>
          </div>
        </div>
      </footer>
    </>
  );
}
