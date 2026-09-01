import { describe, it, expect } from 'vitest';
import { CLIConsoleGuard } from '../../src/permissions/guard.js';

describe('CLIConsoleGuard', () => {
  it('allows safe bash commands when autoApprove is true', () => {
    const guard = new CLIConsoleGuard(true);
    expect(guard.check('bash', { command: 'ls -la' })).toBe(false);
    expect(guard.check('write_file', { path: './foo.txt' })).toBe(false);
  });

  it('blocks dangerous bash commands even in autoApprove mode', () => {
    const guard = new CLIConsoleGuard(true);
    expect(guard.check('bash', { command: 'rm -rf /' })).toBe(true);
    expect(guard.check('bash', { command: 'rm -rf ~' })).toBe(true);
    expect(guard.check('bash', { command: 'rm -rf *' })).toBe(true);
    expect(guard.check('bash', { command: 'rm -rf .' })).toBe(true);
    expect(guard.check('bash', { command: 'rm -rf ./' })).toBe(true);
    expect(guard.check('bash', { command: 'rm -rf ../' })).toBe(true);
    expect(guard.check('bash', { command: 'rm -fr .' })).toBe(true);
  });

  it('requires confirmation for non-allowlisted bash commands in safe mode', () => {
    const guard = new CLIConsoleGuard(false, ['git status', 'npm test']);
    expect(guard.check('bash', { command: 'git status' })).toBe(false);
    expect(guard.check('bash', { command: 'npm test' })).toBe(false);
    expect(guard.check('bash', { command: 'rm file.txt' })).toBe(true);
  });

  it('requires confirmation for files written outside project cwd', () => {
    const guard = new CLIConsoleGuard(false);
    expect(guard.check('write_file', { path: '../../outside.txt' })).toBe(true);
    expect(guard.check('write_file', { path: './local.txt' })).toBe(false);
  });
});
