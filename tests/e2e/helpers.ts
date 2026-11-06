import { spawn, execFileSync } from 'child_process';
import http from 'http';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { existsSync } from 'fs';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(here, '..', '..');

export const canRunE2E = (() => {
  if (process.platform === 'win32') return false;
  try { execFileSync('python3', ['--version'], { stdio: 'ignore' }); } catch { return false; }
  // The CLI is started from dist: it has to be built.
  return existsSync(path.join(ROOT, 'dist', 'cli', 'index.js'));
})();

export interface FakeProvider {
  baseUrl: string;
  requests: any[];
  close(): Promise<void>;
}

/** A tiny OpenAI-compatible server: answers every request with `FAKE-ANSWER to: <your message>`, after `delayMs`. */
export async function startFakeProvider(delayMs = 0): Promise<FakeProvider> {
  const requests: any[] = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      if (req.method === 'GET' && req.url?.includes('/models')) {
        res.setHeader('content-type', 'application/json');
        return res.end(JSON.stringify({ data: [{ id: 'fake' }] }));
      }
      if (req.method === 'POST' && req.url?.includes('/chat/completions')) {
        const parsed = JSON.parse(body || '{}');
        requests.push(parsed);
        const last = [...(parsed.messages || [])].reverse().find((m: any) => m.role === 'user');
        const answer = 'FAKE-ANSWER to: ' + String(last?.content ?? '').slice(0, 40);
        if (parsed.stream === false || parsed.stream === undefined) {
          // not streaming (/compact, the chat title): one JSON answer
          res.writeHead(200, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: answer }, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } }));
        }
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        const send = (o: unknown) => res.write('data: ' + JSON.stringify(o) + '\n\n');
        setTimeout(() => {
          send({ choices: [{ delta: { content: answer } }] });
          send({ choices: [{ delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } });
          res.write('data: [DONE]\n\n');
          res.end();
        }, delayMs);
        return;
      }
      res.statusCode = 404;
      res.end('{}');
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as any).port;
  return { baseUrl: `http://127.0.0.1:${port}/v1`, requests, close: () => new Promise((r) => server.close(() => r())) };
}

export interface Sandbox {
  home: string;
  work: string;
  cleanup(): Promise<void>;
}

/** A throw-away HOME with a config for the fake provider, and a trusted working folder. */
export async function makeSandbox(provider: FakeProvider, extraConfig: Record<string, unknown> = {}): Promise<Sandbox> {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-e2e-home-'));
  const work = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-e2e-work-'));
  const config = {
    provider: 'custom', baseUrl: provider.baseUrl, apiKey: 'k', apiKeys: { custom: 'k' }, baseUrls: { custom: provider.baseUrl },
    model: 'fake', maxContextTokens: 32000, maxIterations: 5, checkUpdates: false, probeEndpoints: false,
    trustedProjects: { [work]: true }, banner: 'clean', theme: 'cyan', onboarded: true, ...extraConfig,
  };
  await fs.writeFile(path.join(home, '.devxrc.json'), JSON.stringify(config));
  return { home, work, cleanup: async () => { await fs.rm(home, { recursive: true, force: true }); await fs.rm(work, { recursive: true, force: true }); } };
}

export interface Step {
  /** Wait until this regex matches the screen text printed since the last key. */
  wait?: string;
  /** Or just wait this long (ms). */
  ms?: number;
  timeout?: number;
  /** Keys to send afterwards. */
  send?: string;
  /** Seconds to let the program react after the keys. */
  after?: number;
}

export interface PtyResult {
  plain: string;
  output: string;
  exited: boolean;
  code: number | null;
  steps: Array<{ wait: string | null; ok: boolean }>;
  seconds: number;
}

/** Starts devx in a real pseudo-terminal (python3 pty) and plays the steps. */
export function runDevx(sandbox: Sandbox, steps: Step[], opts: { exitWait?: number; env?: Record<string, string>; args?: string[] } = {}): Promise<PtyResult> {
  const cfg = {
    cwd: sandbox.work,
    env: { HOME: sandbox.home, USERPROFILE: sandbox.home, ...(opts.env ?? {}) },
    cmd: ['node', path.join(ROOT, 'bin', 'devx.js'), ...(opts.args ?? [])],
    steps,
    exitWait: opts.exitWait ?? 4,
  };
  return new Promise((resolve, reject) => {
    const child = spawn('python3', ['-I', path.join(here, 'pty-driver.py'), JSON.stringify(cfg)], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', (d) => (out += d));
    child.on('error', reject);
    child.on('close', () => {
      try { resolve(JSON.parse(out)); } catch (e) { reject(new Error('pty driver gave no result: ' + out.slice(0, 300))); }
    });
  });
}

export const KEY = { enter: '\r', ctrlC: '\x03', up: '\x1b[A', down: '\x1b[B', esc: '\x1b', ctrlEnter: '\x1b[13;5u' };
