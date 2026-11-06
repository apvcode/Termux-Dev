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

describe('shell commands that read secrets', () => {
  it('are recognised, templates and ordinary files are not', async () => {
    const { shellTouchesSecrets } = await import('../../src/permissions/guard.js');
    for (const cmd of ['cat ~/.devxrc.json', 'cd ~ && cat .devxrc.json', 'head -c 200 ~/.devx/capabilities.json', 'grep key ~/.ssh/config', 'cat "$HOME/.aws/credentials"', 'cat project/.env', 'cat id_rsa', 'less server.pem', 'echo hi; cat ~/.devxrc.json | head']) {
      expect(shellTouchesSecrets(cmd), cmd).toBe(true);
    }
    for (const cmd of ['ls -la', 'cat src/index.ts', 'cat .env.example', 'cat README.md', 'echo $HOME', 'node -v', 'ls ~/Downloads', '']) {
      expect(shellTouchesSecrets(cmd), cmd).toBe(false);
    }
  });

  it('are asked about even in YOLO mode, and the card says why', async () => {
    const { CLIConsoleGuard, buildPermissionRequest } = await import('../../src/permissions/guard.js');
    for (const yolo of [false, true]) {
      const guard = new CLIConsoleGuard(yolo);
      expect(guard.check('bash', { command: 'cat ~/.devxrc.json' })).toBe(true);
      expect(guard.check('bash', { command: 'cat src/index.ts' })).toBe(yolo ? false : true);
    }
    const card = buildPermissionRequest('bash', { command: 'cat ~/.devxrc.json' });
    expect(card.danger).toBe(true);
    expect(card.notes?.join(' ')).toContain('secrets');
  });
});
