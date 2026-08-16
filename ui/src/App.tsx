import React, { useState } from 'react';
import { CreateSessionForm } from './components/CreateSessionForm';
import { JoinSessionForm } from './components/JoinSessionForm';
import { LobbyScreen } from './components/LobbyScreen';
import { GameScreen } from './components/GameScreen';
import { CardCatalogue } from './components/CardCatalogue';
import type { SessionAdmissionDto, SessionStateDto } from './api';

// Storage key for session credentials
const STORAGE_KEY = 'eop_session';

// Stored session interface
interface StoredSession {
  readonly playerToken: string;
  readonly playerId: string;
  readonly sessionId: string;
}

/** Runtime type guard — rejects any stored object missing required string fields. */
function isStoredSession(value: unknown): value is StoredSession {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v['playerToken'] === 'string' &&
    typeof v['playerId'] === 'string' &&
    typeof v['sessionId'] === 'string'
  );
}

// View types
type View =
  | { readonly screen: 'home' }
  | { readonly screen: 'create' }
  | { readonly screen: 'join' }
  | { readonly screen: 'lobby'; readonly sessionId: string; readonly playerId: string; readonly playerToken: string }
  | { readonly screen: 'game'; readonly sessionId: string; readonly playerId: string; readonly playerToken: string; readonly session: SessionStateDto };

/**
 * The application shell with view switching logic.
 */
export default function App(): React.JSX.Element {
  // Feature flags — read at component scope so they are available throughout the
  // component, including in renderView. They must also be checked in the useState
  // initializer below so that a stored session cannot bypass the flag entirely.
  const isGameScreenEnabled = import.meta.env.VITE_GAME_SCREEN_ENABLED === 'true';

  const [view, setView] = useState<View>(() => {
  // The feature flag must be checked here too — not only on the home screen
  // buttons — so that a stored session cannot bypass the flag entirely.
  const isLobbyUiEnabled = import.meta.env.VITE_LOBBY_UI_ENABLED === 'true';
    if (!isLobbyUiEnabled) return { screen: 'home' };

    // Check if we have stored session credentials
    const stored = sessionStorage.getItem(STORAGE_KEY);
    if (stored) {
      try {
        const parsed: unknown = JSON.parse(stored);
        if (isStoredSession(parsed)) {
          return {
            screen: 'lobby',
            sessionId: parsed.sessionId,
            playerId: parsed.playerId,
            playerToken: parsed.playerToken
          };
        }
        // Stored object is missing required fields — discard it
        sessionStorage.removeItem(STORAGE_KEY);
      } catch {
        // Invalid stored data, clear it
        sessionStorage.removeItem(STORAGE_KEY);
      }
    }
    return { screen: 'home' };
  });

  // Handle session admission (after create/join)
  const handleSessionAdmission = (admission: SessionAdmissionDto) => {
    const storedSession: StoredSession = {
      playerToken: admission.playerToken,
      playerId: admission.playerId,
      sessionId: admission.session.sessionId
    };
    
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(storedSession));
    
    setView({
      screen: 'lobby',
      sessionId: admission.session.sessionId,
      playerId: admission.playerId,
      playerToken: admission.playerToken
    });
  };

  // Handle session end (logout/clear storage)
  const handleSessionEnd = () => {
    sessionStorage.removeItem(STORAGE_KEY);
    setView({ screen: 'home' });
  };

  // Handle errors from forms
  const handleError = (message: string) => {
    // Errors are displayed in the forms themselves
    console.error('Form error:', message);
  };

  // Render the appropriate view
  const renderView = () => {
    switch (view.screen) {
      case 'home':
        return <HomeView onViewChange={(screen) => setView({ screen })} />;
      
      case 'create':
        return (
          <div className="govuk-width-container">
            <main className="govuk-main-wrapper" id="main-content">
              <div className="govuk-grid-row">
                <div className="govuk-grid-column-two-thirds">
                  <CreateSessionForm 
                    onSubmit={handleSessionAdmission} 
                    onError={handleError} 
                  />
                </div>
              </div>
            </main>
          </div>
        );
      
      case 'join':
        return (
          <div className="govuk-width-container">
            <main className="govuk-main-wrapper" id="main-content">
              <div className="govuk-grid-row">
                <div className="govuk-grid-column-two-thirds">
                  <JoinSessionForm 
                    onSubmit={handleSessionAdmission} 
                    onError={handleError} 
                  />
                </div>
              </div>
            </main>
          </div>
        );
      
      case 'lobby':
        return (
          <LobbyScreen
            sessionId={view.sessionId}
            playerId={view.playerId}
            playerToken={view.playerToken}
            onSessionEnd={handleSessionEnd}
            onGameStarted={(session) => {
              if (isGameScreenEnabled) {
                setView({
                  screen: 'game',
                  sessionId: view.sessionId,
                  playerId: view.playerId,
                  playerToken: view.playerToken,
                  session
                });
              }
            }}
          />
        );
      
      case 'game':
        return (
          <GameScreen
            sessionId={view.sessionId}
            playerId={view.playerId}
            playerToken={view.playerToken}
            session={view.session}
            onSessionEnd={handleSessionEnd}
          />
        );
    }
  };

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

      {renderView()}

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
                . The threat prompts shown above are Microsoft&apos;s, transcribed
                from the published deck. Attribution is the only obligation the
                licence imposes, and it is discharged here rather than only in the
                repository.
              </p>
            </div>
          </div>
        </div>
      </footer>
    </>
  );
}

interface HomeViewProps {
  readonly onViewChange: (screen: 'create' | 'join') => void;
}

function HomeView({ onViewChange }: HomeViewProps): React.JSX.Element {
  // Check if lobby UI is enabled via environment variable
  const isLobbyUiEnabled = import.meta.env.VITE_LOBBY_UI_ENABLED === 'true';

  return (
    <div className="govuk-width-container">
      <main className="govuk-main-wrapper" id="main-content">
        <h1 className="govuk-heading-xl">Threat modelling card game</h1>

        <p className="govuk-body-l">
          A digital version of the Elevation of Privilege card game, for teams
          who cannot share a table.
        </p>

        <div className="govuk-button-group">
          <button
            type="button"
            className="govuk-button"
            data-module="govuk-button"
            disabled={!isLobbyUiEnabled}
            aria-disabled={!isLobbyUiEnabled}
            onClick={() => onViewChange('create')}
          >
            Create a session
          </button>
          <button
            type="button"
            className="govuk-button govuk-button--secondary"
            data-module="govuk-button"
            disabled={!isLobbyUiEnabled}
            aria-disabled={!isLobbyUiEnabled}
            onClick={() => onViewChange('join')}
          >
            Join a session
          </button>
        </div>

        {!isLobbyUiEnabled && (
          <p className="govuk-hint">
            Creating and joining a session is not available yet.
          </p>
        )}

        <h2 className="govuk-heading-l">The deck</h2>
        <CardCatalogue />
      </main>
    </div>
  );
}