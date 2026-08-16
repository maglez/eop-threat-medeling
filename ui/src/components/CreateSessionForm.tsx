import React, { useState } from 'react';
import { createSession } from '../api';
import { ErrorSummary } from './ErrorSummary';

interface CreateSessionFormProps {
  readonly onSubmit: (admission: import('../api').SessionAdmissionDto) => void;
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

  const validate = (): boolean => {
    const newErrors: string[] = [];
    
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
      const admission = await createSession(displayName.trim());
      onSubmit(admission);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create session';
      setErrors([message]);
      onError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

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
      
      <form onSubmit={handleSubmit} noValidate>
        <div className={`govuk-form-group ${hasDisplayNameError ? 'govuk-form-group--error' : ''}`}>
          <h1 className="govuk-label-wrapper">
            <label className="govuk-label govuk-label--l" htmlFor="display-name">
              Create a session
            </label>
          </h1>
          
          <p className="govuk-hint">
            Enter your name to create a new game session
          </p>
          
          {hasDisplayNameError && (
            <p className="govuk-error-message">
              <span className="govuk-visually-hidden">Error:</span> {errors.find(e => e.includes('name'))}
            </p>
          )}
          
          <input
            type="text"
            id="display-name"
            className={`govuk-input ${hasDisplayNameError ? 'govuk-input--error' : ''}`}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            disabled={isSubmitting}
            autoComplete="name"
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