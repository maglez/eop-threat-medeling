import React, { useState } from 'react';
import { joinSession, type SessionAdmissionDto } from '../api';
import { ErrorSummary } from './ErrorSummary';

interface JoinSessionFormProps {
  readonly onSubmit: (admission: SessionAdmissionDto) => void;
  readonly onError: (message: string) => void;
}

/**
 * Form for joining an existing session.
 * 
 * Collects the join code and player's display name to join a session.
 */
export function JoinSessionForm({ onSubmit, onError }: JoinSessionFormProps): React.JSX.Element {
  const [joinCode, setJoinCode] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [errors, setErrors] = useState<readonly string[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const validate = (): boolean => {
    const newErrors: string[] = [];
    
    if (!joinCode.trim()) {
      newErrors.push('Enter a join code');
    }
    
    if (!displayName.trim()) {
      newErrors.push('Enter your name');
    } else if (displayName.trim().length > 50) {
      newErrors.push('Name must be 50 characters or less');
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
      const admission = await joinSession(joinCode.trim(), displayName.trim());
      onSubmit(admission);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to join session';
      setErrors([message]);
      onError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const hasJoinCodeError = errors.some(error => error.includes('join code'));
  const hasDisplayNameError = errors.some(error => error.includes('name'));

  return (
    <>
      {errors.length > 0 && (
        <ErrorSummary 
          title="There is a problem" 
          errors={errors} 
          onDismiss={() => setErrors([])} 
        />
      )}
      
      <form onSubmit={(e) => { void handleSubmit(e); }} noValidate>
        <div className={`govuk-form-group ${hasJoinCodeError ? 'govuk-form-group--error' : ''}`}>
          <h1 className="govuk-label-wrapper">
            <label className="govuk-label govuk-label--l" htmlFor="join-code">
              Join a session
            </label>
          </h1>
          
          <p id="join-code-hint" className="govuk-hint">
            Enter the join code provided by the facilitator
          </p>
          
          {hasJoinCodeError && (
            <p id="join-code-error" className="govuk-error-message">
              <span className="govuk-visually-hidden">Error:</span> {errors.find(e => e.includes('join code'))}
            </p>
          )}
          
          <input
            type="text"
            id="join-code"
            name="join-code"
            className={`govuk-input govuk-input--width-10 ${hasJoinCodeError ? 'govuk-input--error' : ''}`}
            value={joinCode}
            onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
            disabled={isSubmitting}
            autoComplete="off"
            aria-describedby={`join-code-hint${hasJoinCodeError ? ' join-code-error' : ''}`}
          />
        </div>
        
        <div className={`govuk-form-group ${hasDisplayNameError ? 'govuk-form-group--error' : ''}`}>
          <label className="govuk-label" htmlFor="display-name">
            Your name
          </label>
          
          <p id="display-name-hint" className="govuk-hint">
            Enter your name as you'd like it to appear in the game
          </p>
          
          {hasDisplayNameError && (
            <p id="display-name-error" className="govuk-error-message">
              <span className="govuk-visually-hidden">Error:</span> {errors.find(e => e.includes('name'))}
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
          {isSubmitting ? 'Joining session...' : 'Join a session'}
        </button>
      </form>
    </>
  );
}