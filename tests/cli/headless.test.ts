import { afterEach, describe, expect, it, vi } from 'vitest';
import { runHeadlessMode } from '../../src/cli/headless.js';
import { createProvider } from '../../src/providers/index.js';
import { MCPManager } from '../../src/mcp/manager.js';

vi.mock('../../src/prompts/builder.js', () => ({ buildSystemPrompt: async () => 'System prompt' }));
vi.mock('../../src/providers/index.js', () => ({ createProvider: vi.fn() }));
vi.mock('../../src/tools/index.js', () => ({ getTools: () => [] }));
vi.mock('../../src/mcp/manager.js', () => {
  const manager = { init: vi.fn(async () => []), stopAll: vi.fn() };
  return { MCPManager: { getInstance: () => manager } };
});

afterEach(() => vi.restoreAllMocks());

const config = { maxContextTokens: 10000, maxIterations: 3, model: 'test' };

describe('headless cancellation', () => {
  it('returns 130, reports failure in JSON and releases its signal listener', async () => {
    const controller = new AbortController();
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const listenerCount = process.listenerCount('SIGINT');
    vi.mocked(createProvider).mockReturnValue({
      id: 'test',
      chat: ({ signal }) => new Promise((_resolve, reject) => {
        signal!.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')), { once: true });
        controller.abort();
      }),
    });

    const code = await runHeadlessMode('task', config, { json: true, signal: controller.signal });

    expect(code).toBe(130);
    expect(JSON.parse(log.mock.calls[0][0])).toMatchObject({ success: false, error: 'Operation cancelled by user' });
    expect(process.listenerCount('SIGINT')).toBe(listenerCount);
    expect(MCPManager.getInstance().stopAll).toHaveBeenCalled();
  });

  it('does not contact the provider when the incoming signal is already aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const chat = vi.fn();
    vi.mocked(createProvider).mockReturnValue({ id: 'test', chat });

    expect(await runHeadlessMode('task', config, { json: true, signal: controller.signal })).toBe(130);
    expect(chat).not.toHaveBeenCalled();
  });

  it('releases listeners and stops MCP when initialization fails', async () => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.mocked(MCPManager.getInstance().init).mockRejectedValueOnce(new Error('MCP startup failed'));
    const listenerCount = process.listenerCount('SIGINT');

    expect(await runHeadlessMode('task', config, { json: true })).toBe(1);
    expect(process.listenerCount('SIGINT')).toBe(listenerCount);
    expect(MCPManager.getInstance().stopAll).toHaveBeenCalled();
  });
});
