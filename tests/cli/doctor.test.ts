import { describe, it, expect } from 'vitest';
import { checkOllamaContext, MIN_OLLAMA_CONTEXT } from '../../src/cli/doctor.js';

const reply = (routes: Record<string, unknown | null>): typeof fetch =>
  (async (url: any) => {
    const path = new URL(String(url)).pathname;
    const body = routes[path];
    if (body === undefined || body === null) return new Response('', { status: 404 });
    return new Response(JSON.stringify(body), { status: 200 });
  }) as any;

const cfg = { provider: 'ollama', model: 'lfm3', baseUrl: 'http://127.0.0.1:11434/v1' };

describe('checkOllamaContext', () => {
  it('ignores other providers', async () => {
    expect(await checkOllamaContext({ provider: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1' }, reply({}))).toBeNull();
    expect(await checkOllamaContext({ provider: 'ollama-cloud', baseUrl: 'https://ollama.com/v1' }, reply({}))).toBeNull();
  });

  it('warns when Ollama is not running', async () => {
    const r = await checkOllamaContext(cfg, reply({}));
    expect(r?.status).toBe('warn');
    expect(r?.details).toContain('ollama serve');
  });

  it('warns when the loaded window is too small', async () => {
    const r = await checkOllamaContext(cfg, reply({
      '/api/version': { version: '0.12.0' },
      '/api/ps': { models: [{ name: 'lfm3', context_length: 4096 }] },
    }));
    expect(r?.status).toBe('warn');
    expect(r?.details).toContain('4096');
    expect(r?.details).toContain(`OLLAMA_CONTEXT_LENGTH=${MIN_OLLAMA_CONTEXT}`);
  });

  it('is ok when the window is large enough', async () => {
    const r = await checkOllamaContext(cfg, reply({
      '/api/version': { version: '0.12.0' },
      '/api/ps': { models: [{ name: 'lfm3', context_length: 32768 }] },
    }));
    expect(r).toMatchObject({ status: 'ok', details: '32768 tokens' });
  });

  it('says it cannot tell when no model is loaded', async () => {
    const r = await checkOllamaContext(cfg, reply({ '/api/version': { version: '0.12.0' }, '/api/ps': { models: [] } }));
    expect(r?.status).toBe('warn');
    expect(r?.details).toContain('not loaded');
  });
});
