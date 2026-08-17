import React, { useState } from 'react';
import { createSession, type SessionAdmissionDto } from '../api';
import { ErrorSummary } from './ErrorSummary';

interface CreateSessionFormProps {
  readonly onSubmit: (admission: SessionAdmissionDto) => void;
  readonly onError: (message: string) => void;
}

/**
 * Form for creating a new session.
 * 
 * Collects the facilitator's display name and creates a new session.
 */
export function CreateSessionForm({ onSubmit, onError }: CreateSessionFormProps): React.JSX.Element {
  const [displayName, setDisplayName] = useState('');
  const [errors, setErrors] = useState<readonly string[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Error kinds are prefixed so field-highlighting can match on a stable token
  // rather than a substring of human-readable prose (which is case-sensitive and
  // can change without breaking the predicate).
  const DISPLAY_NAME_ERROR_PREFIX = 'display-name:';

  const validate = (): boolean => {
    const newErrors: string[] = [];
    
    if (!displayName.trim()) {
      newErrors.push(`${DISPLAY_NAME_ERROR_PREFIX}Enter your name`);
    } else if (displayName.trim().length > 50) {
      newErrors.push(`${DISPLAY_NAME_ERROR_PREFIX}Name must be 50 characters or less`);
    }
    
    setErrors(newErrors);
    return newErrors.length === 0;
  };

  const handleSubmit = async (e: React.FormEvent): Promise<void> => {
    e.preventDefault();
    
    if (!validate()) {
      return;
    }
    
    setIsSubmitting(true);
    
    try {
      const admission = await createSession(displayName.trim());
      onSubmit(admission);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create session';
      // Server errors are not field-specific; show them without a field prefix so
      // they appear in the ErrorSummary but do not trigger field-level highlighting.
      setErrors([message]);
      onError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const hasDisplayNameError = errors.some(error => error.startsWith(DISPLAY_NAME_ERROR_PREFIX));

  return (
    <>
      {errors.length > 0 && (
        <ErrorSummary 
          title="There is a problem" 
          errors={errors.map(e => e.startsWith(DISPLAY_NAME_ERROR_PREFIX) ? e.slice(DISPLAY_NAME_ERROR_PREFIX.length) : e)} 
          onDismiss={() => setErrors([])} 
        />
      )}
      
      <form onSubmit={(e) => { void handleSubmit(e); }} noValidate>
        <div className={`govuk-form-group ${hasDisplayNameError ? 'govuk-form-group--error' : ''}`}>
          <h1 className="govuk-label-wrapper">
            <label className="govuk-label govuk-label--l" htmlFor="display-name">
              Create a session
            </label>
          </h1>
          
          <p id="display-name-hint" className="govuk-hint">
            Enter your name to create a new game session
          </p>
          
          {hasDisplayNameError && (
            <p id="display-name-error" className="govuk-error-message">
              <span className="govuk-visually-hidden">Error:</span> {errors.find(e => e.startsWith(DISPLAY_NAME_ERROR_PREFIX))?.replace(DISPLAY_NAME_ERROR_PREFIX, '')}
            </p>
          )}
          
          <input
            type="text"
            id="display-name"
            name="display-name"
            className={`govuk-input ${hasDisplayNameError ? 'govuk-input--error' : ''}`}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            disabled={isSubmitting}
            autoFocus
            autoComplete="name"
            aria-describedby={`display-name-hint${hasDisplayNameError ? ' display-name-error' : ''}`}
          />
        </div>
        
        <button 
          type="submit" 
          className="govuk-button" 
          data-module="govuk-button"
          disabled={isSubmitting}
        >
          {isSubmitting ? 'Creating session...' : 'Create a session'}
        </button>
      </form>
    </>
  );
}