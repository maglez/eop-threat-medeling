import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { JoinSessionForm } from './JoinSessionForm';
import type { SessionAdmissionDto } from '../api';

const mockAdmission: SessionAdmissionDto = {
  playerToken: 'test-token',
  playerId: 'player-2',
  session: {
    sessionId: 'session-1',
    joinCode: 'ABC123',
    status: 'LOBBY',
    players: [
      {
        playerId: 'player-1',
        displayName: 'Facilitator',
        seatOrder: 0,
        role: 'FACILITATOR',
        connectionStatus: 'CONNECTED'
      },
      {
        playerId: 'player-2',
        displayName: 'Test User',
        seatOrder: 1,
        role: 'PARTICIPANT',
        connectionStatus: 'CONNECTED'
      }
    ],
    createdAt: '2023-01-01T00:00:00Z',
    updatedAt: '2023-01-01T00:00:00Z'
  }
};

describe('JoinSessionForm', () => {
  const mockOnSubmit = vi.fn();
  const mockOnError = vi.fn();
  
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the form with correct elements', () => {
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    expect(screen.getByRole('heading', { level: 1, name: 'Join a session' })).toBeInTheDocument();
    expect(screen.getByLabelText('Join a session')).toBeInTheDocument();
    expect(screen.getByLabelText('Your name')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Join a session' })).toBeInTheDocument();
    expect(screen.getByText('Enter the join code provided by the facilitator')).toBeInTheDocument();
    expect(screen.getByText('Enter your name as you\'d like it to appear in the game')).toBeInTheDocument();
  });

  it('shows validation error when join code is empty', async () => {
    const user = userEvent.setup();
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.click(screen.getByRole('button', { name: 'Join a session' }));
    
    expect(screen.getByText('Enter a join code', { selector: 'li' }).closest('li')).toBeInTheDocument();
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });

  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup();
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Join a session'), 'ABC123');
    await user.click(screen.getByRole('button', { name: 'Join a session' }));
    
    expect(screen.getByText('Enter your name', { selector: 'li' }).closest('li')).toBeInTheDocument();
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });

  it('shows validation error when name is too long', async () => {
    const user = userEvent.setup();
    const longName = 'A'.repeat(51); // 51 characters, exceeds 50 limit
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Join a session'), 'ABC123');
    await user.type(screen.getByLabelText('Your name'), longName);
    await user.click(screen.getByRole('button', { name: 'Join a session' }));
    
    // The error appears in both the ErrorSummary list and the field-level error message
    expect(screen.getAllByText('Name must be 50 characters or less').length).toBeGreaterThanOrEqual(1);
    // Field-level error styling is applied
    expect(document.querySelector('.govuk-form-group--error')).toBeInTheDocument();
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });

  it('submits form with valid join code and name', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(() => 
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(mockAdmission)
      } as Response)
    ));
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Join a session'), 'ABC123');
    await user.type(screen.getByLabelText('Your name'), 'Test User');
    await user.click(screen.getByRole('button', { name: 'Join a session' }));
    
    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalledWith(mockAdmission);
    });
  });

  it('converts join code to uppercase', async () => {
    const user = userEvent.setup();
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    const joinCodeInput = screen.getByLabelText('Join a session');
    await user.type(joinCodeInput, 'abc123');
    
    expect(joinCodeInput).toHaveValue('ABC123');
  });

  it('shows error when API call fails', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(() => 
      Promise.resolve({
        ok: false,
        status: 404,
        json: () => Promise.resolve({ title: 'Not Found', detail: 'Session not found' })
      } as Response)
    ));
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Join a session'), 'ABC123');
    await user.type(screen.getByLabelText('Your name'), 'Test User');
    await user.click(screen.getByRole('button', { name: 'Join a session' }));
    
    await waitFor(() => {
      expect(screen.getByText('Session not found')).toBeInTheDocument();
      expect(mockOnError).toHaveBeenCalledWith('Session not found');
      expect(mockOnSubmit).not.toHaveBeenCalled();
    });
  });

  it('disables submit button while submitting', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {}))); // Never resolving promise
    
    render(<JoinSessionForm onSubmit={mockOnSubmit} onError={mockOnError} />);
    
    await user.type(screen.getByLabelText('Join a session'), 'ABC123');
    await user.type(screen.getByLabelText('Your name'), 'Test User');
    const submitButton = screen.getByRole('button', { name: 'Join a session' });
    
    expect(submitButton).not.toBeDisabled();
    
    await user.click(submitButton);
    
    expect(submitButton).toBeDisabled();
    expect(submitButton).toHaveTextContent('Joining session...');
  });
});