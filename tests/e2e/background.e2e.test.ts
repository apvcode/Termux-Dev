import http from 'node:http';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { KEY, Sandbox, canRunE2E, makeSandbox, runDevx } from './helpers.js';

// A background task that ends while devx waits at the prompt: the agent hears about it by itself.
// The fake model is scripted: step 1 starts the task, step 2 ends the turn, step 3 answers the news.
interface Scripted { baseUrl: string; streamed: any[]; close(): Promise<void> }

async function startScriptedProvider(command: string): Promise<Scripted> {
  const streamed: any[] = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      if (req.method === 'GET') { res.setHeader('content-type', 'application/json'); return res.end(JSON.stringify({ data: [{ id: 'fake' }] })); }
      const parsed = JSON.parse(body || '{}');
      if (!parsed.stream) {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'Task chat' }, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
      }
      streamed.push(parsed);
      const n = streamed.length;
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      const send = (o: unknown) => res.write('data: ' + JSON.stringify(o) + '\n\n');
      if (n === 1) {
        const args = JSON.stringify({ action: 'start', command, label: 'demo server' });
        send({ choices: [{ delta: { tool_calls: [{ index: 0, id: 'call_1', type: 'function', function: { name: 'background', arguments: args } }] } }] });
        send({ choices: [{ delta: {}, finish_reason: 'tool_calls' }], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } });
      } else {
        const text = n === 2 ? 'STARTED-OK it runs' : 'SAW-THE-NEWS';
        send({ choices: [{ delta: { content: text } }] });
        send({ choices: [{ delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } });
      }
      res.write('data: [DONE]\n\n');
      res.end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as any).port;
  return { baseUrl: `http://127.0.0.1:${port}/v1`, streamed, close: () => new Promise((r) => server.close(() => r())) };
}

describe.skipIf(!canRunE2E)('background tasks in a terminal', () => {
  let provider: Scripted;
  let box: Sandbox;
  const command = `node -e "console.log('server ready'); setTimeout(()=>{console.error('crashed'); process.exit(1)}, 3000)"`;
  beforeEach(async () => { provider = await startScriptedProvider(command); box = await makeSandbox({ baseUrl: provider.baseUrl, requests: [], close: async () => {} }); });
  afterEach(async () => { await provider.close(); await box.cleanup(); });

  it('shows the task in the frame and wakes the agent when it ends', async () => {
    const r = await runDevx(box, [
      { wait: 'Ask or describe', timeout: 30 },
      { ms: 300, send: 'start the server' },
      { ms: 200, send: KEY.enter },
      { wait: 'STARTED-OK', timeout: 20 },
      { wait: '1 bg', timeout: 10 },                       // the badge in the frame while the task runs
      { wait: 'SAW-THE-NEWS', timeout: 25, send: KEY.ctrlC },   // the task crashed, the agent answered without a message of the user
      { wait: 'Exit devx', timeout: 10, send: KEY.up },
      { ms: 200, send: KEY.enter },
    ], { exitWait: 6, args: ['--yolo'] });

    expect(r.steps.map((s) => s.ok)).toEqual([true, true, true, true, true, true, true, true]);
    expect(r.plain).toContain('demo server');
    const last = JSON.stringify(provider.streamed[provider.streamed.length - 1].messages);
    expect(last).toContain('[devx · background tasks]');
    expect(last).toContain('bg1 \\"demo server\\" failed (exit code 1)');
    expect(last).toContain('crashed');
    expect(provider.streamed).toHaveLength(3);
    expect(r.exited).toBe(true);
  }, 120000);
});
