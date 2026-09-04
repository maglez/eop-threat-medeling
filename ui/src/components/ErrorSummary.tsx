import React from 'react';

interface ErrorSummaryProps {
  readonly title?: string;
  readonly errors: readonly string[];
  readonly onDismiss?: () => void;
}

/**
 * A reusable GOV.UK error summary component.
 * 
 * Displays a list of errors in the standard GOV.UK error summary format.
 */
export function ErrorSummary({ title = 'There is a problem', errors, onDismiss }: ErrorSummaryProps): React.JSX.Element {
  // The message text is the key, so React reconciles a changing error list by
  // identity rather than by position. That requires the keys to be unique, and
  // de-duplicating is how it is guaranteed rather than assumed — an error
  // summary that lists the same message twice is a defect in its own right,
  // since the reader cannot tell the two entries apart.
  const uniqueErrors = [...new Set(errors)];

  return (
    <div className="govuk-error-summary" data-module="govuk-error-summary" role="alert" tabIndex={-1}>
      <h2 className="govuk-error-summary__title">
        {title}
      </h2>
      <div className="govuk-error-summary__body">
        <ul className="govuk-list govuk-error-summary__list">
          {uniqueErrors.map((error) => (
            <li key={error}>{error}</li>
          ))}
        </ul>
      </div>
      {onDismiss && (
        <button 
          type="button" 
          className="govuk-button govuk-button--secondary govuk-!-margin-top-2"
          onClick={onDismiss}
        >
          Dismiss
        </button>
      )}
    </div>
  );
}