import { afterEach, describe, expect, it } from 'vitest';
import { BackgroundManager, cleanOutput, formatDuration } from '../../src/core/background.js';

const managers: BackgroundManager[] = [];
const make = () => {
  const m = new BackgroundManager();
  managers.push(m);
  return m;
};
afterEach(async () => {
  await Promise.all(managers.splice(0).map((m) => m.stopAll()));
});

function node(script: string): string {
  const encoded = Buffer.from(script).toString('base64');
  return `"${process.execPath}" -e "eval(Buffer.from('${encoded}','base64').toString())"`;
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function until(check: () => boolean, ms = 4000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (check()) return;
    await sleep(20);
  }
  throw new Error('condition not reached');
}

describe('background tasks', () => {
  it('starts a command, keeps it running and reads its output', async () => {
    const m = make();
    const info = m.start(node("console.log('hello'); setInterval(()=>{},1000)"), { label: 'greeter' });
    expect(info.id).toBe('bg1');
    expect(info.status).toBe('running');
    await until(() => m.read('bg1', { mode: 'tail' }).text.includes('hello'));
    expect(m.running()).toHaveLength(1);
  });

  it('reads only what is new since the last read', async () => {
    const m = make();
    m.start(node("console.log('one'); setTimeout(()=>{console.log('two')},300); setInterval(()=>{},1000)"));
    await until(() => m.read('bg1', { mode: 'tail' }).text.includes('one'));
    expect(m.read('bg1').text).toContain('one');
    expect(m.read('bg1').text).toBe('');
    await until(() => m.read('bg1', { mode: 'tail' }).text.includes('two'));
    expect(m.read('bg1').text).toBe('two');
  });

  it('filters lines with grep and limits the lines', async () => {
    const m = make();
    m.start(node("for (let i=0;i<50;i++) console.log(i%10===0 ? 'ERROR '+i : 'ok '+i)"));
    await until(() => m.get('bg1')!.status !== 'running');
    expect(m.read('bg1', { mode: 'tail', grep: '^ERROR' }).text.split('\n')).toHaveLength(5);
    const limited = m.read('bg1', { mode: 'tail', lines: 3 });
    expect(limited.text.split('\n')).toHaveLength(3);
    expect(limited.omitted).toBe(47);
  });

  it('records the exit code and tells the model when a task fails', async () => {
    const m = make();
    m.start(node("console.log('boom'); process.exit(3)"), { label: 'broken' });
    await until(() => m.get('bg1')!.status !== 'running');
    expect(m.get('bg1')).toMatchObject({ status: 'failed', code: 3 });
    expect(m.hasNotes()).toBe(true);
    const text = m.takeNotes();
    expect(text).toContain('bg1 "broken" failed (exit code 3)');
    expect(text).toContain('boom');
    expect(m.hasNotes()).toBe(false);
    expect(m.takeNotes()).toBe('');
  });

  it('a task that finishes cleanly is "exited" with code 0', async () => {
    const m = make();
    m.start(node("console.log('done')"));
    await until(() => m.get('bg1')!.status !== 'running');
    expect(m.get('bg1')).toMatchObject({ status: 'exited', code: 0 });
  });

  it('reports a line that matches notify_on, once for a burst', async () => {
    const m = make();
    const seen: string[] = [];
    m.on('note', (n) => seen.push(n.kind));
    m.start(node("console.log('starting'); setTimeout(()=>{console.log('server READY on 3000');console.log('READY again')},100); setInterval(()=>{},1000)"), { notifyOn: 'ready' });
    await until(() => m.hasNotes());
    const text = m.takeNotes();
    expect(text).toContain('matching /ready/');
    expect(text).toContain('server READY on 3000');
    expect(seen).toEqual(['match']);
  });

  it('stopping a task ends it and gives no failure note', async () => {
    const m = make();
    m.start(node('setInterval(()=>{},1000)'));
    const info = await m.stop('bg1');
    expect(info.status).toBe('stopped');
    expect(m.hasNotes()).toBe(false);
    expect(m.running()).toHaveLength(0);
  });

  it('stops a whole process tree', async () => {
    const m = make();
    // The shell starts node as a child; stopping must reach it.
    m.start(node("console.log(process.pid); setInterval(()=>{},1000)"));
    await until(() => /\d+/.test(m.read('bg1', { mode: 'tail' }).text));
    const pid = Number(m.read('bg1', { mode: 'tail' }).text.trim());
    await m.stop('bg1');
    await sleep(300);
    expect(() => process.kill(pid, 0)).toThrow();
  });

  it('wait returns when a line matches', async () => {
    const m = make();
    m.start(node("setTimeout(()=>console.log('listening on 8080'),200); setInterval(()=>{},1000)"));
    const result = await m.wait('bg1', { pattern: 'listening', timeoutSeconds: 5 });
    expect(result).toEqual({ reason: 'match', line: 'listening on 8080' });
  });

  it('wait ignores lines the model has already read', async () => {
    const m = make();
    m.start(node("console.log('ready'); setInterval(()=>{},1000)"));
    await until(() => m.read('bg1', { mode: 'tail' }).text.includes('ready'));
    m.read('bg1');   // the model has read it
    const result = await m.wait('bg1', { pattern: 'ready', timeoutSeconds: 1 });
    expect(result.reason).toBe('timeout');
  });

  it('wait counts output nobody has read yet', async () => {
    const m = make();
    m.start(node("console.log('ready'); setInterval(()=>{},1000)"));
    await until(() => m.read('bg1', { mode: 'tail' }).text.includes('ready'));
    expect(await m.wait('bg1', { pattern: 'ready', timeoutSeconds: 1 })).toEqual({ reason: 'match', line: 'ready' });
  });

  it('wait returns at once for a task that has ended, and when the task ends', async () => {
    const m = make();
    m.start(node("setTimeout(()=>{},200)"));
    expect((await m.wait('bg1', { timeoutSeconds: 5 })).reason).toBe('exit');
    expect((await m.wait('bg1', { timeoutSeconds: 5 })).reason).toBe('exit');
  });

  it('wait can be cancelled', async () => {
    const m = make();
    m.start(node('setInterval(()=>{},1000)'));
    const controller = new AbortController();
    const waiting = m.wait('bg1', { timeoutSeconds: 30, signal: controller.signal });
    setTimeout(() => controller.abort(), 50);
    expect((await waiting).reason).toBe('aborted');
  });

  it('a task stops itself when its time limit runs out, and says so', async () => {
    const m = make();
    m.start(node('setInterval(()=>{},1000)'), { maxRuntimeSeconds: 0.3 });
    await until(() => m.get('bg1')!.status !== 'running', 5000);
    expect(m.takeNotes()).toContain('time limit');
  });

  it('refuses more than the allowed number of tasks, and unknown ids', async () => {
    const m = make();
    for (let i = 0; i < 8; i++) m.start(node('setInterval(()=>{},1000)'));
    expect(() => m.start('echo hi')).toThrow(/Too many/);
    expect(() => m.read('bg99')).toThrow(/No background task "bg99"/);
    expect(() => m.start('echo', { notifyOn: '(' })).toThrow();
  });

  it('keeps only the tail of a very chatty task', async () => {
    const m = make();
    m.start(node("const l='x'.repeat(99); for (let i=0;i<5000;i++) console.log(i+' '+l); console.log('LAST')"));
    await until(() => m.get('bg1')!.status !== 'running', 8000);
    const { text } = m.read('bg1', { mode: 'tail', lines: 5 });
    expect(text.split('\n').pop()).toBe('LAST');
    expect(m.get('bg1')!.outputChars).toBeGreaterThan(400_000);
  });

  it('clearFinished forgets ended tasks only', async () => {
    const m = make();
    m.start(node('setInterval(()=>{},1000)'));
    m.start(node("console.log('x')"));
    await until(() => m.get('bg2')!.status !== 'running');
    expect(m.clearFinished()).toBe(1);
    expect(m.list().map((t) => t.id)).toEqual(['bg1']);
  });
});

describe('helpers', () => {
  it('cleans colour codes and progress redraws', () => {
    expect(cleanOutput('\x1b[32mok\x1b[0m')).toBe('ok');
    expect(cleanOutput('10%\r50%\r100%\nnext')).toBe('100%\nnext');
    expect(cleanOutput('a\r\nb')).toBe('a\nb');
  });

  it('formats durations', () => {
    expect(formatDuration(4000)).toBe('4s');
    expect(formatDuration(125000)).toBe('2m 05s');
    expect(formatDuration(3_900_000)).toBe('1h 05m');
  });
});
