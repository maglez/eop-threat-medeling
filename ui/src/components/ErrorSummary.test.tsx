import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ErrorSummary } from './ErrorSummary';

describe('ErrorSummary', () => {
  it('renders with default title and errors', () => {
    const errors = ['First error message', 'Second error message'];
    
    render(<ErrorSummary errors={errors} />);
    
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('There is a problem')).toBeInTheDocument();
    expect(screen.getByText('First error message')).toBeInTheDocument();
    expect(screen.getByText('Second error message')).toBeInTheDocument();
  });

  it('renders with custom title', () => {
    const errors = ['Error message'];
    
    render(<ErrorSummary title="Custom title" errors={errors} />);
    
    expect(screen.getByText('Custom title')).toBeInTheDocument();
    expect(screen.getByText('Error message')).toBeInTheDocument();
  });

  it('renders dismiss button when onDismiss is provided', () => {
    const errors = ['Error message'];
    const onDismiss = vi.fn();
    
    render(<ErrorSummary errors={errors} onDismiss={onDismiss} />);
    
    const dismissButton = screen.getByRole('button', { name: 'Dismiss' });
    expect(dismissButton).toBeInTheDocument();
    
    dismissButton.click();
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('renders a repeated message once, so every list key is unique', () => {
    const errors = ['Enter a value', 'Enter a value', 'Choose an option'];

    render(<ErrorSummary errors={errors} />);

    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByText('Enter a value')).toBeInTheDocument();
    expect(screen.getByText('Choose an option')).toBeInTheDocument();
  });

  it('does not render dismiss button when onDismiss is not provided', () => {
    const errors = ['Error message'];
    
    render(<ErrorSummary errors={errors} />);
    
    expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument();
  });
});