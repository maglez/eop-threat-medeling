import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CreateSessionForm } from './CreateSessionForm';
import type { SessionAdmissionDto } from '../api';

const mockAdmission: SessionAdmissionDto = {
  playerToken: 'test-token',
  playerId: 'player-1',
  session: {
    sessionId: 'session-1',
    joinCode: 'ABC123',
    status: 'LOBBY',
    players: [
      {
        playerId: 'player-1',
        displayName: 'Test User',
        seatOrder: 0,
        role: 'FACILITATOR',
        connectionStatus: 'CONNECTED'
      }
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z'
  }
};

describe('CreateSessionForm', () => {
  const mockOnSubmit = vi.fn();
  const mockOnError = vi.fn();
  
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the form with correct elements', () => {
    render(<CreateSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    expect(screen.getByRole('heading', { level: 1, name: 'Create a session' })).toBeInTheDocument();
    expect(screen.getByLabelText('Create a session')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create a session' })).toBeInTheDocument();
    expect(screen.getByText('Enter your name to create a new game session')).toBeInTheDocument();
  });

  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup();
    
    render(<CreateSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.click(screen.getByRole('button', { name: 'Create a session' }));
    
    expect(screen.getByText('Enter your name', { selector: 'li' })).toBeInTheDocument();
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });

  it('shows validation error when name is too long', async () => {
    const user = userEvent.setup();
    const longName = 'A'.repeat(51); // 51 characters, exceeds 50 limit
    
    render(<CreateSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Create a session'), longName);
    await user.click(screen.getByRole('button', { name: 'Create a session' }));
    
    // The error appears in both the ErrorSummary list and the field-level error message
    expect(screen.getAllByText('Name must be 50 characters or less').length).toBeGreaterThanOrEqual(1);
    // Field-level error styling is applied
    expect(document.querySelector('.govuk-form-group--error')).toBeInTheDocument();
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });

  it('submits form with valid name', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(() => 
      Promise.resolve({
        ok: true,
        status: 201,
        json: () => Promise.resolve(mockAdmission)
      } as Response)
    ));
    
    render(<CreateSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Create a session'), 'Test User');
    await user.click(screen.getByRole('button', { name: 'Create a session' }));
    
    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalledWith(mockAdmission);
    });
  });

  it('shows error when API call fails', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(() => 
      Promise.resolve({
        ok: false,
        status: 400,
        json: () => Promise.resolve({ title: 'Bad Request', detail: 'Invalid request' })
      } as Response)
    ));
    
    render(<CreateSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Create a session'), 'Test User');
    await user.click(screen.getByRole('button', { name: 'Create a session' }));
    
    await waitFor(() => {
      expect(screen.getByText('Invalid request')).toBeInTheDocument();
      expect(mockOnError).toHaveBeenCalledWith('Invalid request');
      expect(mockOnSubmit).not.toHaveBeenCalled();
    });
  });

  it('disables submit button while submitting', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {}))); // Never resolving promise
    
    render(<CreateSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Create a session'), 'Test User');
    const submitButton = screen.getByRole('button', { name: 'Create a session' });
    
    expect(submitButton).not.toBeDisabled();
    
    await user.click(submitButton);
    
    expect(submitButton).toBeDisabled();
    expect(submitButton).toHaveTextContent('Creating session...');
  });
});