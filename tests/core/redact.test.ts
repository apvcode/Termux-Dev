import { describe, expect, it } from 'vitest';
import { containsSecret, redactSecrets } from '../../src/core/redact.js';

// built at run time so that no key-shaped literal sits in the repository
const FAKE_OR_KEY = ['sk-or', 'v1', '0123456789abcdef'.repeat(4)].join('-');

describe('redactSecrets', () => {
  it('hides API keys and tokens, keeping the start so the key can be recognised', () => {
    const text = [
      '"custom": "sk-ih-iKFY0L5Ko1GDUcogz_ijixegogQuvZ0FNdUOhcje2to",',
      `"openrouter": "${FAKE_OR_KEY}"`,
      'token ghp_abcdefghijklmnopqrstuvwxyz0123456789 and github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz',
      'slack xoxb-1234567890-abcdefghijkl',
      'aws AKIAIOSFODNN7EXAMPLE google AIzaSyA1234567890abcdefghijklmnopqrstuvw',
    ].join('\n');
    const out = redactSecrets(text);
    expect(out).not.toContain('iKFY0L5Ko1GDUcogz');
    expect(out).not.toContain('56049140a48948fff1c23f30e43b5f5e');
    expect(out).not.toContain('abcdefghijklmnopqrstuvwxyz0123456789');
    expect(out).not.toContain('IOSFODNN7EXAMPLE');
    expect(out).toContain('sk-ih…[hidden secret]');
    expect(out).toContain('sk-or…[hidden secret]');
    expect(out).toContain('ghp_…[hidden secret]');
  });

  it('hides the value of secret-looking settings and authorization headers, keeps the name', () => {
    expect(redactSecrets('{"apiKey": "supersecretvalue123", "model": "x"}')).toBe('{"apiKey": "[hidden secret]", "model": "x"}');
    expect(redactSecrets('API_KEY=abcd1234efgh5678')).toBe('API_KEY=[hidden secret]');
    expect(redactSecrets('password: "hunter2hunter2"')).toBe('password: "[hidden secret]"');
    expect(redactSecrets('Authorization: Bearer abcdefghijklmnop1234567890')).toBe('Authorization: Bearer [hidden secret]');
  });

  it('hides private key blocks, also a cut off one', () => {
    const key = '-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\nAAAA\n-----END OPENSSH PRIVATE KEY-----';
    expect(redactSecrets(`before\n${key}\nafter`)).toBe('before\n[hidden private key]\nafter');
    expect(redactSecrets('x\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow')).toBe('x\n[hidden private key]');
  });

  it('leaves ordinary text alone: hashes, ids, short values, code, words like token', () => {
    for (const text of [
      'commit 5b7a93c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7 fixed it',
      'const id = "e3dc63cf-a98a-447f-9bdf-295bede9a46e";',
      'the token count is 4096 and password rules are strict',
      'tokens: 12',
      'export const model = "deepseek-v4.1-flash";',
      'Plan & Tasks 0/6',
      'sk-short',
      '',
    ]) {
      expect(redactSecrets(text), text).toBe(text);
    }
  });

  it('is safe on non-strings and says whether something was hidden', () => {
    expect(redactSecrets(undefined as any)).toBe(undefined);
    expect(containsSecret('hello')).toBe(false);
    expect(containsSecret('key sk-abcdefghijklmnopqrstuvwx')).toBe(true);
  });
});

describe('the agent loop hides secrets in tool results', () => {
  it('the model and the saved history get the redacted text', async () => {
    const { Agent } = await import('../../src/core/loop.js');
    const { History } = await import('../../src/core/history.js');
    let step = 0;
    const provider: any = {
      chat: async () => (++step === 1
        ? { content: '', toolCalls: [{ id: 'c1', name: 'leak', arguments: {} }] }
        : { content: 'done' }),
    };
    const tool: any = {
      name: 'leak',
      definition: { name: 'leak', description: 'x', parameters: { type: 'object', properties: {} } },
      validateArgs() {},
      execute: async () => `key = ${FAKE_OR_KEY}`,
    };
    const history = new History();
    history.addMessage({ role: 'system', content: 's' });
    history.addMessage({ role: 'user', content: 'go' });
    const agent = new Agent({ maxIterations: 5, maxContextTokens: 100000 } as any, provider, [tool], history, { check: () => false, askUser: async () => true } as any);
    const events: any[] = [];
    for await (const e of agent.run()) events.push(e);
    const toolMsg = history.getMessages().find((m) => m.role === 'tool')!;
    expect(toolMsg.content).not.toContain('56049140a48948fff1c23f30e43b5f5e');
    expect(toolMsg.content).toContain('[hidden secret]');
    expect(events.find((e) => e.type === 'tool_end').result).not.toContain('56049140a48948fff1c23f30e43b5f5e');
  });
});
