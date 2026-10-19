import { afterEach, describe, expect, it, vi } from 'vitest';
import { reportRuntimeError } from '../../src/cli/runtime_errors.js';

const originalExitCode = process.exitCode;
afterEach(() => {
  process.exitCode = originalExitCode;
  vi.restoreAllMocks();
});

describe('runtime errors', () => {
  it('reports the stack, marks failure and cancels the active operation', () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const abort = vi.fn();
    const error = new Error('Request failed');
    reportRuntimeError(error, abort);
    expect(log).toHaveBeenCalledWith('\n[devx runtime error]:', error.stack);
    expect(process.exitCode).toBe(1);
    expect(abort).toHaveBeenCalledOnce();
  });

  it('reports rejection reasons that are not Error objects', () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    reportRuntimeError('Connection lost');
    expect(log).toHaveBeenCalledWith('\n[devx runtime error]:', 'Connection lost');
  });
});
