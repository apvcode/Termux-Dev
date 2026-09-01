import { describe, it, expect } from 'vitest';

describe('Abort & Cancellation Lifecycle', () => {
  it('aborts cleanly during connecting phase', async () => {
    const abortController = new AbortController();
    let isConnecting = true;
    let cancelledMessage = '';

    const stopGeneration = () => {
      abortController.abort();
      if (isConnecting) {
        cancelledMessage = 'Connection cancelled';
      } else {
        cancelledMessage = 'Generation stopped';
      }
    };

    // Simulate user hitting Ctrl+C while connecting
    stopGeneration();

    expect(abortController.signal.aborted).toBe(true);
    expect(cancelledMessage).toBe('Connection cancelled');
  });

  it('aborts during active generation phase', async () => {
    const abortController = new AbortController();
    let isConnecting = false;
    let cancelledMessage = '';

    const stopGeneration = () => {
      abortController.abort();
      if (isConnecting) {
        cancelledMessage = 'Connection cancelled';
      } else {
        cancelledMessage = 'Generation stopped';
      }
    };

    // Simulate user hitting Ctrl+C while streaming response
    stopGeneration();

    expect(abortController.signal.aborted).toBe(true);
    expect(cancelledMessage).toBe('Generation stopped');
  });
});
