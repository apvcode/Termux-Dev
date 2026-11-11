import fs from 'fs';
import os from 'os';
import path from 'path';
import { beforeAll, describe, expect, it } from 'vitest';
import type { LLMProvider, LLMRequest, LLMResponse, LLMStreamChunk } from '../../src/core/types.js';

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'devx-rt-'));
process.env.DEVX_SESSIONS_DIR = path.join(work, 'sessions');
process.env.DEVX_CONFIG_PATH = path.join(work, 'config.json');
process.env.DEVX_REMOTE_DIR = path.join(work, 'remote');

/** A model that follows a script: each request takes the next step. */
function scripted(steps: Array<(req: LLMRequest) => LLMResponse>): LLMProvider {
  let i = 0;
  return {
    id: 'fake',
    async chat(req) { return steps[Math.min(i++, steps.length - 1)](req); },
    async *chatStream(req): AsyncGenerator<LLMStreamChunk, LLMResponse, unknown> {
      const res = steps[Math.min(i++, steps.length - 1)](req);
      if (res.content) for (const part of res.content.match(/.{1,6}/g) ?? []) yield { type: 'content_delta', delta: part };
      yield { type: 'done', response: res };
      return res;
    },
  };
}

const usage = { promptTokens: 100, completionTokens: 10, totalTokens: 110, cost: 0.001 };

beforeAll(() => {
  fs.mkdirSync(work, { recursive: true });
  fs.writeFileSync(process.env.DEVX_CONFIG_PATH!, JSON.stringify({ provider: 'fake', model: 'fake-model', baseUrl: 'http://127.0.0.1:1/v1', apiKey: 'k', maxContextTokens: 50000 }));
});

async function make(provider: LLMProvider, extra: Record<string, any> = {}) {
  const { SessionRuntime } = await import('../../src/remote/runtime.js');
  const events: any[] = [];
  const cwd = fs.mkdtempSync(path.join(work, 'proj-'));
  const rt = new SessionRuntime({ cwd, emit: (e) => events.push(e), rpc: async () => 'none', providerFactory: () => provider, ...extra });
  await rt.init();
  const waitFor = async (pred: (e: any) => boolean, ms = 4000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      const hit = events.find(pred);
      if (hit) return hit;
      await new Promise((r) => setTimeout(r, 10));
    }
    throw new Error('timeout waiting; events: ' + events.map((e) => e.e).join(','));
  };
  return { rt, events, waitFor, cwd };
}

describe('session runtime', () => {
  it('runs a plain turn: streams text, reports usage, saves the chat', async () => {
    const { rt, events, waitFor } = await make(scripted([() => ({ content: 'Hello there', usage })]));
    await rt.send('hi');
    await waitFor((e) => e.e === 'turn_end');
    expect(events.filter((e) => e.e === 'text').map((e) => e.d).join('')).toBe('Hello there');
    const end = events.find((e) => e.e === 'turn_end');
    expect(end.cost).toBeCloseTo(0.001);
    expect(events.some((e) => e.e === 'user' && e.text === 'hi')).toBe(true);
    const snap: any = rt.snapshot();
    expect(snap.items.map((i: any) => i.k)).toEqual(['user', 'assistant']);
    expect(snap.state.title).toBe('hi');
    expect(fs.readdirSync(process.env.DEVX_SESSIONS_DIR!).some((f) => f.endsWith('.json'))).toBe(true);
  });

  it('asks the phone before a command runs, and runs it only when allowed', async () => {
    const call = { id: 'c1', name: 'bash', arguments: { command: 'echo remote-ok' } };
    const { rt, events, waitFor } = await make(scripted([
      () => ({ content: 'Running it.', toolCalls: [call], usage }),
      (req) => ({ content: 'Result: ' + req.messages.filter((m) => m.role === 'tool').map((m) => m.content).join('|'), usage }),
    ]));
    await rt.send('run it');
    const perm = await waitFor((e) => e.e === 'perm');
    expect(perm.title).toBe('bash');
    expect(perm.lines.join('\n')).toContain('echo remote-ok');
    // a phone that joins now sees the question
    expect((rt.snapshot() as any).live.perm.id).toBe(perm.id);
    expect(rt.answerPermission(perm.id, 'allow')).toBe(true);
    await waitFor((e) => e.e === 'turn_end');
    const end = events.find((e) => e.e === 'tool_end');
    expect(end.result).toContain('remote-ok');
    expect(events.filter((e) => e.e === 'text').map((e) => e.d).join('')).toContain('Result:');
  });

  it('says "denied" to the model when the phone refuses', async () => {
    const call = { id: 'c1', name: 'bash', arguments: { command: 'echo nope' } };
    const { rt, events, waitFor } = await make(scripted([
      () => ({ content: '', toolCalls: [call], usage }),
      (req) => ({ content: 'saw: ' + req.messages.filter((m) => m.role === 'tool').map((m) => m.content).join('|'), usage }),
    ]));
    await rt.send('x');
    const perm = await waitFor((e) => e.e === 'perm');
    rt.answerPermission(perm.id, 'deny');
    await waitFor((e) => e.e === 'turn_end');
    expect(events.find((e) => e.e === 'tool_end').result).toContain('denied');
  });

  it('stops a turn that waits for an answer', async () => {
    const call = { id: 'c1', name: 'bash', arguments: { command: 'echo never' } };
    const { rt, events, waitFor } = await make(scripted([() => ({ content: '', toolCalls: [call], usage })]));
    await rt.send('x');
    await waitFor((e) => e.e === 'perm');
    rt.stop();
    const end = await waitFor((e) => e.e === 'turn_end');
    expect(end.aborted).toBe(true);
    expect(events.some((e) => e.e === 'perm_done')).toBe(true);
  });

  it('turns questions of the model into a card for the phone', async () => {
    const call = { id: 'q1', name: 'ask_questions', arguments: { questions: [{ question: 'Which stack?', options: ['Node', 'Python'] }] } };
    const { rt, events, waitFor } = await make(scripted([
      () => ({ content: '', toolCalls: [call], usage }),
      (req) => ({ content: 'ok ' + req.messages.filter((m) => m.role === 'tool').map((m) => m.content).join(''), usage }),
    ]));
    await rt.send('build');
    const ask = await waitFor((e) => e.e === 'ask');
    expect(ask.questions[0].options).toEqual(['Node', 'Python']);
    rt.answerQuestions(ask.id, ['Python']);
    await waitFor((e) => e.e === 'turn_end');
    expect(events.find((e) => e.e === 'tool_end').result).toContain('A: Python');
  });

  it('queues a message sent while the model works and answers it next', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const seen: string[] = [];
    const provider: LLMProvider = {
      id: 'slow',
      async chat(req) {
        seen.push(req.messages.filter((m) => m.role === 'user').map((m) => m.content).join('|'));
        if (seen.length === 1) await gate;
        return { content: 'answer ' + seen.length, usage };
      },
    };
    const { rt, waitFor, events } = await make(provider);
    await rt.send('first');
    await waitFor((e) => e.e === 'turn_start');
    const second = await rt.send('second');
    expect(second.queued).toBe(true);
    release();
    await waitFor((e) => e.e === 'queued_taken' || events.filter((x) => x.e === 'turn_end').length >= 2);
    await new Promise((r) => setTimeout(r, 200));
    expect(seen.join(' ## ')).toContain('first|second');
  });

  it('shows the name of the person to the model and offers the phone memory tool', async () => {
    let system = '';
    let toolNames: string[] = [];
    const provider: LLMProvider = { id: 'p', async chat(req) { system = req.messages[0].content; toolNames = (req.tools ?? []).map((t) => t.name); return { content: 'hi', usage }; } };
    const { rt, waitFor } = await make(provider);
    rt.setProfile({ name: 'Mega', memory: true });
    await new Promise((r) => setTimeout(r, 100));
    await rt.send('hello');
    await waitFor((e) => e.e === 'turn_end');
    expect(system).toContain('Mega');
    expect(system).toContain('phone_memory');
    expect(toolNames).toContain('phone_memory');
  });

  it('answers phone_memory through the host', async () => {
    const call = { id: 'm1', name: 'phone_memory', arguments: { action: 'search', query: 'email' } };
    const asked: any[] = [];
    const { rt, events, waitFor } = await make(scripted([
      () => ({ content: '', toolCalls: [call], usage }),
      (req) => ({ content: 'got ' + req.messages.filter((m) => m.role === 'tool').map((m) => m.content).join(''), usage }),
    ]), { rpc: async (method: string, params: any) => { asked.push([method, params]); return 'email: a@b.c'; } });
    rt.setProfile({ name: 'M', memory: true });
    await rt.send('what is my email');
    await waitFor((e) => e.e === 'turn_end');
    expect(asked).toEqual([['phone_memory', { action: 'search', query: 'email' }]]);
    expect(events.find((e) => e.e === 'tool_end').result).toBe('email: a@b.c');
  });

  it('compacts, renames and starts a fresh chat', async () => {
    const { rt, events, waitFor } = await make(scripted([() => ({ content: 'one', usage }), () => ({ content: 'a summary of things', usage })]));
    await rt.send('first message');
    await waitFor((e) => e.e === 'turn_end');
    await rt.send('second');
    await waitFor((e) => events.filter((x) => x.e === 'turn_end').length === 2);
    const before = rt.sessionId();
    const res = await rt.compact();
    expect(res.ok).toBe(true);
    expect((rt.snapshot() as any).items[0].k).toBe('compact');
    await rt.rename('My chat');
    expect((rt.state() as any).title).toBe('My chat');
    await rt.reset();
    expect(rt.sessionId()).not.toBe(before);
    expect((rt.snapshot() as any).items).toEqual([]);
  });

  it('resumes a saved chat with its history', async () => {
    const first = await make(scripted([() => ({ content: 'remember me', usage })]));
    await first.rt.send('hello');
    await first.waitFor((e) => e.e === 'turn_end');
    const id = first.rt.sessionId();
    const { SessionRuntime } = await import('../../src/remote/runtime.js');
    const again = new SessionRuntime({ cwd: first.cwd, sessionId: id, emit: () => {}, rpc: async () => '', providerFactory: () => scripted([() => ({ content: 'x', usage })]) });
    await again.init();
    expect((again.snapshot() as any).items.map((i: any) => i.text)).toEqual(['hello', 'remember me']);
  });
});
