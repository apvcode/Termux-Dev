import { afterEach, describe, expect, it, vi } from 'vitest';

// Colours on, so that the animation (which is made of colours) can be seen in the output.
vi.hoisted(() => { process.env.FORCE_COLOR = '1'; });

import {
  SPINNER_FRAMES, estimateProgress, extractHighlights, manualCommand, presentUpdate, renderBar, renderChecking,
  renderFailureCard, renderProgressCard, renderUpdateCard, runManualUpdate, runStartupUpdateCheck, shimmer,
} from '../../src/cli/update-screen.js';
import { LiveBlock } from '../../src/cli/liveblock.js';
import { CommandRunner, RunResult, UpdateCheckResult } from '../../src/cli/updater.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { LANGUAGE_IDS, setLanguage } from '../../src/core/i18n.js';
import { displayWidth, stripAnsi } from '../../src/cli/width.js';

setActiveTheme('cyan');
afterEach(() => setLanguage('en'));

const NOTES = `<div align="center">

# devx 1.9.1

**A headline**

</div>

---

## English

### Highlights

- **Your own providers.** \`/provider\` → **Add a provider**: a name, an address and a key.
  - a sub point that is not a highlight
- **Esc stops.** While the agent works, [Esc](https://example.com) stops the answer.
- Plain bullet without bold.

### Fixed in this release

- This one belongs to the next block.

---

## Русский

### Главное

- **Свои провайдеры.** \`/provider\` → **Добавить провайдера**: имя, адрес и ключ.
- **Esc останавливает.** Пока агент работает, Esc останавливает ответ.

### Исправлено

- Это из другого блока.
`;

describe('shimmer', () => {
  it('keeps the text and only changes the colours as the frames go by', () => {
    const text = 'Checking for updates';
    expect(stripAnsi(shimmer(text, 0))).toBe(text);
    expect(stripAnsi(shimmer(text, 9))).toBe(text);
    expect(shimmer(text, 0)).not.toBe(shimmer(text, 9));       // the bright band has moved
    expect(shimmer(text, 3)).toBe(shimmer(text, 3));           // the same frame is the same picture
    expect(stripAnsi(shimmer('日本語 ok', 4))).toBe('日本語 ok');
    expect(shimmer('', 5)).toBe('');
  });
});

describe('renderBar', () => {
  it('is exactly as wide as asked, whatever the progress and the tone', () => {
    for (const width of [4, 10, 33, 70]) {
      for (const progress of [0, 0.01, 0.5, 0.99, 1, 7, -3]) {
        for (const tone of ['busy', 'done', 'failed'] as const) {
          for (const tick of [0, 1, 17]) {
            expect(displayWidth(renderBar(width, progress, tick, tone)), `${width} ${progress} ${tone}`).toBe(width);
          }
        }
      }
    }
  });

  it('fills as far as the progress says, with a head only while it is busy', () => {
    const bar = (p: number, tone: any = 'busy') => stripAnsi(renderBar(20, p, 0, tone));
    expect(bar(0.5)).toBe('━'.repeat(10) + '╸' + '─'.repeat(9));
    expect(bar(0)).toBe('╸' + '─'.repeat(19));
    expect(bar(1)).toBe('━'.repeat(20));
    expect(bar(0.5, 'done')).toBe('━'.repeat(10) + '─'.repeat(10));
  });
});

describe('estimateProgress', () => {
  const ids = ['registry', 'install', 'verify'] as const;

  it('keeps moving while a step runs, and never reaches the end before the steps are done', () => {
    const at = (ms: number) => estimateProgress([...ids], 1, ms);
    expect(at(0)).toBeLessThan(at(2000));
    expect(at(2000)).toBeLessThan(at(20000));
    expect(at(10 * 60 * 1000)).toBeLessThan(1);
  });

  it('counts the finished steps by their weight (the install is nearly all of it) and is 1 when all are done', () => {
    expect(estimateProgress([...ids], 0, 0)).toBe(0);
    expect(estimateProgress([...ids], 1, 0)).toBeCloseTo(0.1, 6);
    expect(estimateProgress([...ids], 2, 0)).toBeCloseTo(0.85, 6);
    expect(estimateProgress([...ids], 3, 0)).toBe(1);
  });
});

describe('extractHighlights', () => {
  it('takes the bullets of the first block in the language of the interface, with the markdown taken off', () => {
    expect(extractHighlights(NOTES, 'en', 4)).toEqual([
      'Your own providers. /provider → Add a provider: a name, an address and a key.',
      'Esc stops. While the agent works, Esc stops the answer.',
      'Plain bullet without bold.',
    ]);
    expect(extractHighlights(NOTES, 'ru', 4)).toEqual([
      'Свои провайдеры. /provider → Добавить провайдера: имя, адрес и ключ.',
      'Esc останавливает. Пока агент работает, Esc останавливает ответ.',
    ]);
  });

  it('falls back to English for a language the notes have no section for, and stops at the maximum', () => {
    expect(extractHighlights(NOTES, 'ja', 4)[0]).toContain('Your own providers.');
    expect(extractHighlights(NOTES, 'en', 2)).toHaveLength(2);
  });

  it('copes with notes that have no sections, no headings, or nothing at all', () => {
    expect(extractHighlights('- one\n- two', 'en')).toEqual(['one', 'two']);
    expect(extractHighlights('just a paragraph', 'en')).toEqual([]);
    expect(extractHighlights('', 'en')).toEqual([]);
    expect(extractHighlights(undefined, 'en')).toEqual([]);
  });
});

const info = {
  current: '1.9.0', latest: '1.9.1', publishedAt: '2026-11-08T10:00:00Z',
  highlights: extractHighlights(NOTES, 'en', 4), method: 'npm' as const,
};
const steps = [
  { label: 'Checking the registry', status: 'ok' as const, ms: 600 },
  { label: 'Installing devx 1.9.1', status: 'run' as const },
  { label: 'Checking the new version', status: 'wait' as const },
];

describe('the screens', () => {
  it('say the versions, the date, what is new and how it is installed', () => {
    const text = renderUpdateCard(info, 80).map(stripAnsi).join('\n');
    expect(text).toContain('v1.9.0 → v1.9.1');
    expect(text).toContain('devx 1.9.1 is out');
    expect(text).toContain('You have 1.9.0 · released 2026-11-08');
    expect(text).toContain("WHAT'S NEW");
    expect(text).toContain('Your own providers.');
    expect(text).toContain('Installed with npm');
    expect(renderUpdateCard({ ...info, method: 'git' }, 80).map(stripAnsi).join('\n')).toContain('git clone');
    expect(renderUpdateCard({ ...info, highlights: [], publishedAt: undefined }, 80).map(stripAnsi).join('\n')).not.toContain("WHAT'S NEW");
  });

  it('draw every step with its state, its time, and the bar', () => {
    const text = renderProgressCard({ current: '1.9.0', latest: '1.9.1', steps, progress: 0.4, tone: 'busy' }, 80, 3).map(stripAnsi).join('\n');
    expect(text).toMatch(/✓ Checking the registry\s+0\.6s/);
    expect(text).toContain(`${SPINNER_FRAMES[3]} Installing devx 1.9.1`);
    expect(text).toMatch(/○ Checking the new version/);
    expect(text).toContain('━');
    expect(text).toContain('Keep devx open');
    const done = renderProgressCard({ current: '1.9.0', latest: '1.9.1', steps: steps.map((s) => ({ ...s, status: 'ok' as const })), progress: 0.3, tone: 'done', restarting: true }, 80, 3).map(stripAnsi).join('\n');
    expect(done).toContain('updated');
    expect(done).toContain('Restarting devx…');
    expect(done).toContain('devx 1.9.1 is installed');
  });

  it('a failure says where it stopped, shows the end of the output, and gives the command to do it by hand', () => {
    const failure = { current: '1.9.0', latest: '1.9.1', step: 'Installing devx 1.9.1', message: 'a\nb\nc\nd\ne\nf', notPublished: false, method: 'npm' as const };
    const text = renderFailureCard(failure, 80).map(stripAnsi).join('\n');
    expect(text).toContain('update failed');
    expect(text).toContain('Stopped at: Installing devx 1.9.1');
    expect(text).toMatch(/\n│\s+c\s+│\n/);                              // the last four lines of the output
    expect(text).not.toMatch(/│\s+a\s+│/);
    expect(text).toContain('devx keeps running on 1.9.0.');
    expect(text).toContain('npm install -g termux-dev@latest');
    expect(renderFailureCard({ ...failure, notPublished: true }, 80).map(stripAnsi).join('\n')).toContain('npm does not have 1.9.1 yet');
    expect(manualCommand('git')).toBe('git pull && npm install && npm run build');
  });

  it('fit every width of terminal in every language', () => {
    const failure = { current: '1.9.0', latest: '1.9.1', step: 'Installing devx 1.9.1', message: 'npm error code EACCES\nnpm error path /usr/lib/node_modules/termux-dev', notPublished: false, method: 'git' as const };
    for (const id of LANGUAGE_IDS) {
      setLanguage(id);
      for (const cols of [26, 36, 40, 59, 60, 80, 120, 200]) {
        const screens = [
          [renderChecking(cols, 5, '1.9.0')],
          renderUpdateCard({ ...info, highlights: extractHighlights(NOTES, id, 4) }, cols),
          renderProgressCard({ current: '1.9.0', latest: '1.9.1', steps, progress: 0.4, tone: 'busy' }, cols, 4),
          renderProgressCard({ current: '1.9.0', latest: '1.9.1', steps, progress: 0.4, tone: 'done', restarting: true }, cols, 4),
          renderFailureCard(failure, cols),
        ];
        for (const lines of screens) {
          for (const line of lines) expect(displayWidth(line), `${id} ${cols}: ${stripAnsi(line)}`).toBeLessThanOrEqual(cols - 1);
          // in the framed card every row of the box is as wide as the top border
          const box = lines.map(stripAnsi).filter((l) => /^[╭│╰]/.test(l));
          if (box.length > 0) expect(new Set(box.map(displayWidth)).size, `${id} ${cols}`).toBe(1);
        }
      }
    }
  });
});

describe('LiveBlock.release', () => {
  it('leaves what is drawn, shows the cursor again, and the next draw starts under it', () => {
    const chunks: string[] = [];
    const block = new LiveBlock({ columns: 80, write: (c) => { chunks.push(c); return true; } });
    block.draw(['one', 'two']);
    block.release();
    expect(chunks[chunks.length - 1]).toBe('\n\x1b[?25h');
    block.draw(['three']);
    expect(chunks[chunks.length - 1].startsWith('\x1b[?25l\r')).toBe(true);   // no climb back into the old block
    expect(chunks[chunks.length - 1]).not.toMatch(/\x1b\[\d+A/);
  });
});

// ───────────────────────── the whole flow, with a user and a machine that are made up ─────────────────────────

function screen(columns = 80, isTTY = true) {
  const chunks: string[] = [];
  const out = { columns, isTTY, write: (c: string) => { chunks.push(c); return true; }, on: () => undefined, off: () => undefined };
  return { out, text: () => stripAnsi(chunks.join('')) };
}

const available: UpdateCheckResult = { updateAvailable: true, currentVersion: '1.9.0', latestVersion: '1.9.1', releaseNotes: NOTES, publishedAt: '2026-11-08T10:00:00Z' };
const lsJson = (version: string) => JSON.stringify({ dependencies: { 'termux-dev': { version } } });

function machine(script: Record<string, Partial<RunResult>>, delay = 10) {
  const calls: Array<{ command: string; cwd?: string }> = [];
  const run: CommandRunner = async (command, args, options) => {
    const key = `${command} ${args.join(' ')}`;
    calls.push({ command: key, cwd: options?.cwd });
    await new Promise((r) => setTimeout(r, delay));
    return { code: 0, output: '', ...(script[key] ?? {}) };
  };
  return { run, calls };
}

const user = (answer: unknown) => {
  const seen: any[] = [];
  return { picker: (async (options: any) => { seen.push(options); return answer; }) as any, seen };
};
const fast = { tickMs: 5, restartMs: 30 };
const NPM = { method: 'npm' as const, root: '/somewhere' };
const npmOk = { 'npm view termux-dev@1.9.1 version --prefer-online': { output: '1.9.1\n' }, 'npm ls -g termux-dev --depth=0 --json': { output: lsJson('1.9.1') } };

describe('presentUpdate', () => {
  it('shows the card and goes no further when nobody can answer (not a terminal)', async () => {
    const s = screen(80, false);
    const m = machine({});
    expect(await presentUpdate(available, { offerSkip: true }, { output: s.out, install: NPM, run: m.run })).toBe('later');
    expect(s.text()).toContain('devx 1.9.1 is out');
    expect(m.calls).toEqual([]);
  });

  it('"Later" installs nothing; "Skip" is remembered; Esc is "later"', async () => {
    const save = vi.fn(async () => undefined);
    const m = machine({});
    const later = user('later');
    expect(await presentUpdate(available, { offerSkip: true, save }, { output: screen().out, install: NPM, run: m.run, picker: later.picker })).toBe('later');
    expect(await presentUpdate(available, { offerSkip: true, save }, { output: screen().out, install: NPM, run: m.run, picker: user(undefined).picker })).toBe('later');
    expect(save).not.toHaveBeenCalled();

    const s = screen();
    expect(await presentUpdate(available, { offerSkip: true, save }, { output: s.out, install: NPM, run: m.run, picker: user('skip').picker })).toBe('skipped');
    expect(save).toHaveBeenCalledWith('1.9.1');
    expect(s.text()).toContain('no more questions about 1.9.1');
    expect(m.calls).toEqual([]);
  });

  it('offers the three choices at the start, two for /update, with the version in the text', async () => {
    const a = user('later');
    await presentUpdate(available, { offerSkip: true }, { output: screen().out, install: NPM, picker: a.picker });
    expect(a.seen[0].items.map((i: any) => i.value)).toEqual(['now', 'later', 'skip']);
    expect(a.seen[0].items[0].detail).toBe('Install 1.9.1 and restart devx');
    expect(a.seen[0].current).toBe('now');
    const b = user('later');
    await presentUpdate(available, { offerSkip: false }, { output: screen().out, install: NPM, picker: b.picker });
    expect(b.seen[0].items.map((i: any) => i.value)).toEqual(['now', 'later']);
  });

  it('installs exactly the version it announced, step by step on a live screen, and restarts devx', async () => {
    const s = screen();
    const m = machine(npmOk, 25);
    const restart = vi.fn();
    const result = await presentUpdate(available, { offerSkip: true }, { output: s.out, install: NPM, run: m.run, picker: user('now').picker, restart, ...fast });
    expect(result).toBe('updated');
    expect(m.calls.map((c) => c.command)).toEqual([
      'npm view termux-dev@1.9.1 version --prefer-online',
      'npm install -g termux-dev@1.9.1 --prefer-online',
      'npm ls -g termux-dev --depth=0 --json',
    ]);
    expect(restart).toHaveBeenCalledTimes(1);
    const text = s.text();
    for (const word of ['updating', 'Checking the registry', 'Installing devx 1.9.1', 'Checking the new version', 'updated', 'Restarting devx…']) expect(text, word).toContain(word);
    expect(text).toContain('✓');
    expect(text).not.toContain('update failed');
  });

  it('a version that is on GitHub but not in npm yet is reported, and nothing is installed', async () => {
    const s = screen();
    const m = machine({ 'npm view termux-dev@1.9.1 version --prefer-online': { output: '1.9.0\n' } });
    const restart = vi.fn();
    const result = await presentUpdate(available, { offerSkip: false }, { output: s.out, install: NPM, run: m.run, picker: user('now').picker, restart, ...fast });
    expect(result).toBe('failed');
    expect(m.calls).toHaveLength(1);
    expect(restart).not.toHaveBeenCalled();
    expect(s.text()).toContain('npm does not have 1.9.1 yet');
    expect(s.text()).toContain('npm install -g termux-dev@latest');
  });

  it('a failed install shows where it stopped and the end of what the command wrote; no restart, devx goes on', async () => {
    const s = screen();
    const m = machine({
      'npm view termux-dev@1.9.1 version --prefer-online': { output: '1.9.1\n' },
      'npm install -g termux-dev@1.9.1 --prefer-online': { code: 243, output: 'npm error code EACCES\nnpm error syscall mkdir\nnpm error path /usr/lib/node_modules\n' },
    });
    const restart = vi.fn();
    const result = await presentUpdate(available, { offerSkip: false }, { output: s.out, install: NPM, run: m.run, picker: user('now').picker, restart, ...fast });
    expect(result).toBe('failed');
    expect(m.calls).toHaveLength(2);                               // the check of the new version never ran
    expect(restart).not.toHaveBeenCalled();
    const text = s.text();
    expect(text).toContain('Stopped at: Installing devx 1.9.1');
    expect(text).toContain('npm error code EACCES');
    expect(text).toContain('devx keeps running on 1.9.0.');
  });

  it('refuses to call it done when npm reports another version afterwards', async () => {
    const s = screen();
    const m = machine({ ...npmOk, 'npm ls -g termux-dev --depth=0 --json': { output: lsJson('1.9.0') } });
    const restart = vi.fn();
    expect(await presentUpdate(available, { offerSkip: false }, { output: s.out, install: NPM, run: m.run, picker: user('now').picker, restart, ...fast })).toBe('failed');
    expect(restart).not.toHaveBeenCalled();
    expect(s.text()).toContain('npm reports 1.9.0 instead of 1.9.1');
  });

  it('a git copy is pulled, built and linked in its own folder', async () => {
    const s = screen();
    const m = machine({});
    const restart = vi.fn();
    const result = await presentUpdate(available, { offerSkip: false }, { output: s.out, install: { method: 'git', root: '/dev/devx' }, run: m.run, picker: user('now').picker, restart, ...fast });
    expect(result).toBe('updated');
    expect(m.calls).toEqual([
      { command: 'git pull --ff-only origin main', cwd: '/dev/devx' },
      { command: 'npm install', cwd: '/dev/devx' },
      { command: 'npm run build', cwd: '/dev/devx' },
      { command: 'npm link', cwd: '/dev/devx' },
    ]);
    expect(s.text()).toContain('Pulling from GitHub');
    expect(restart).toHaveBeenCalledTimes(1);
  });

  it('the link at the end is a convenience: when it fails the update still counts', async () => {
    const m = machine({ 'npm link': { code: 1, output: 'EEXIST' } });
    const restart = vi.fn();
    expect(await presentUpdate(available, { offerSkip: false }, { output: screen().out, install: { method: 'git', root: '/x' }, run: m.run, picker: user('now').picker, restart, ...fast })).toBe('updated');
    expect(restart).toHaveBeenCalledTimes(1);
  });

  it('a git pull that fails stops before anything is built', async () => {
    const m = machine({ 'git pull --ff-only origin main': { code: 1, output: 'fatal: Not possible to fast-forward, aborting.' } });
    const s = screen();
    expect(await presentUpdate(available, { offerSkip: false }, { output: s.out, install: { method: 'git', root: '/x' }, run: m.run, picker: user('now').picker, restart: vi.fn(), ...fast })).toBe('failed');
    expect(m.calls).toHaveLength(1);
    expect(s.text()).toContain('Not possible to fast-forward');
    expect(s.text()).toContain('git pull && npm install && npm run build');
  });
});

describe('the check', () => {
  const noUpdate: UpdateCheckResult = { updateAvailable: false, currentVersion: '1.9.0', latestVersion: '1.9.0' };

  it('at start-up it is off when the setting says so, and a pipe is left alone', async () => {
    const check = vi.fn(async () => available);
    await runStartupUpdateCheck({ checkUpdates: false }, { output: screen().out, check });
    await runStartupUpdateCheck({}, { output: screen(80, false).out, check });
    expect(check).not.toHaveBeenCalled();
  });

  it('plays the checking line while it waits, and takes it away', async () => {
    const s = screen();
    await runStartupUpdateCheck({}, { output: s.out, check: async () => { await new Promise((r) => setTimeout(r, 60)); return noUpdate; }, tickMs: 5 });
    expect(s.text()).toContain('Checking for updates');
    expect(s.text()).toContain('devx is up to date (1.9.0)');
  });

  it('at start-up an offline machine is not worth a word, and a skipped version is not asked about again', async () => {
    const quiet = screen();
    await runStartupUpdateCheck({}, { output: quiet.out, check: async () => ({ ...noUpdate, error: 'Timeout (6s)' }), tickMs: 5 });
    expect(quiet.text()).not.toContain('Could not');
    expect(quiet.text()).not.toContain('up to date');

    const picked = user('now');
    const s = screen();
    await runStartupUpdateCheck({ skippedUpdate: '1.9.1' }, { output: s.out, check: async () => available, picker: picked.picker, tickMs: 5 });
    expect(picked.seen).toEqual([]);
    expect(s.text()).not.toContain('devx 1.9.1 is out');
  });

  it('at start-up a new version is offered, and "Skip" is saved in the config', async () => {
    const config: any = {};
    const save = vi.fn(async () => undefined);
    await runStartupUpdateCheck(config, { output: screen().out, check: async () => available, picker: user('skip').picker, install: NPM, save, tickMs: 5 });
    expect(config.skippedUpdate).toBe('1.9.1');
    expect(save).toHaveBeenCalledWith(config);
    // a newer version than the skipped one is offered again
    const picked = user('later');
    await runStartupUpdateCheck({ skippedUpdate: '1.9.0' }, { output: screen().out, check: async () => available, picker: picked.picker, install: NPM, tickMs: 5 });
    expect(picked.seen).toHaveLength(1);
  });

  it('/update always answers: up to date, could not check, or the card; and it has no "Skip"', async () => {
    const a = screen();
    await runManualUpdate({ output: a.out, check: async () => noUpdate, tickMs: 5 });
    expect(a.text()).toContain('devx is up to date (1.9.0)');
    const b = screen();
    await runManualUpdate({ output: b.out, check: async () => ({ ...noUpdate, error: 'getaddrinfo ENOTFOUND' }), tickMs: 5 });
    expect(b.text()).toContain('Could not check for updates (getaddrinfo ENOTFOUND)');
    const picked = user('later');
    await runManualUpdate({ output: screen().out, check: async () => available, picker: picked.picker, install: NPM, tickMs: 5 });
    expect(picked.seen[0].items.map((i: any) => i.value)).toEqual(['now', 'later']);
  });
});
