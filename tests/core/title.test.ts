import { describe, expect, it } from 'vitest';
import { RESTORE_TERMINAL_TITLE, SAVE_TERMINAL_TITLE, cleanTitle, generateChatTitle, provisionalTitle, terminalTitleSequence } from '../../src/core/title.js';
import { kittyNotificationSequence, notificationBody, sendDesktopNotification } from '../../src/core/notify.js';
import { SessionManager } from '../../src/core/session.js';

describe('cleanTitle', () => {
  it('keeps one short line: no quotes, prefixes, markup or final punctuation', () => {
    expect(cleanTitle('"Небо над Ключами."')).toBe('Небо над Ключами');
    expect(cleanTitle('Title: Fix the build')).toBe('Fix the build');
    expect(cleanTitle('Название: Стих о небе')).toBe('Стих о небе');
    expect(cleanTitle('**Roblox Studio check**\nsecond line')).toBe('Roblox Studio check');
    expect(cleanTitle('<think>hmm</think>\nPlan the poem')).toBe('Plan the poem');
    expect(cleanTitle('«Тест MCP»')).toBe('Тест MCP');
    expect(cleanTitle('')).toBe('');
    expect(cleanTitle(null)).toBe('');
    expect(cleanTitle('"" ')).toBe('');
  });

  it('cuts a long title at a word', () => {
    const t = cleanTitle('a very long title that goes on and on and on and never stops being long');
    expect(Array.from(t).length).toBeLessThanOrEqual(40);
    expect(t.endsWith(' ')).toBe(false);
    expect('a very long title that goes on and on and on'.startsWith(t)).toBe(true);
  });

  it('a provisional title is the start of the first message', () => {
    expect(provisionalTitle('придумай мне стих об небе длинной 10к слов, пожалуйста, очень красивый и длинный')).toMatch(/^придумай мне стих/);
    expect(Array.from(provisionalTitle('x '.repeat(100))).length).toBeLessThanOrEqual(40);
  });
});

describe('generateChatTitle', () => {
  it('asks for a title of the first exchange and cleans the answer', async () => {
    let asked: any;
    const title = await generateChatTitle({ chat: async (req) => { asked = req; return { content: '"Stih ob nebe"\n' }; } }, 'write a poem', 'Here is the poem');
    expect(title).toBe('Stih ob nebe');
    expect(asked.messages[0].role).toBe('system');
    expect(asked.messages[0].content).toContain('language the user writes in');
    expect(asked.messages[1].content).toContain('write a poem');
    expect(asked.messages[1].content).toContain('Here is the poem');
  });

  it('never throws: a failing or silent model gives an empty title', async () => {
    expect(await generateChatTitle({ chat: async () => { throw new Error('boom'); } }, 'a', 'b')).toBe('');
    expect(await generateChatTitle({ chat: async () => ({ content: null }) }, 'a', 'b')).toBe('');
    expect(await generateChatTitle({ chat: () => new Promise(() => {}) }, 'a', 'b', 30)).toBe('');
  });
});

describe('the title of the terminal window', () => {
  it('is the chat name behind a symbol, without control characters', () => {
    expect(terminalTitleSequence('Небо над Ключами')).toBe('\x1b]0;◈ Небо над Ключами\x07');
    expect(terminalTitleSequence('bad\x1b]0;x\x07 title')).not.toMatch(/\x1b\]0;◈ .*[\x1b\x07].*\x07/);
    expect(terminalTitleSequence('')).toBe('\x1b]0;devx\x07');
  });

  it('saves and gives back the title the terminal had', () => {
    expect(SAVE_TERMINAL_TITLE).toBe('\x1b[22;0t');
    expect(RESTORE_TERMINAL_TITLE).toBe('\x1b[23;0t');
  });
});

describe('the session keeps a generated title', () => {
  it('is not renamed by the first message again', async () => {
    const m = new SessionManager('model', false);
    expect(m.hasGeneratedTitle).toBe(false);
    m.setTitle('Short name');
    expect(m.hasGeneratedTitle).toBe(true);
    expect(m.getSession().title).toBe('Short name');
  });
});

describe('notificationBody', () => {
  it('is the start of the answer as plain text', () => {
    expect(notificationBody('## Done\n\nI **fixed** the `build` and [pushed](https://x.y) it.\n\n```ts\nconst a = 1;\n```\n- next step')).toBe('Done I fixed the build and pushed it. next step');
    expect(notificationBody('')).toBe('');
  });

  it('cuts a long answer at a word and says so', () => {
    const body = notificationBody('word '.repeat(100), 40);
    expect(Array.from(body).length).toBeLessThanOrEqual(41);
    expect(body.endsWith('…')).toBe(true);
    expect(body).not.toContain('wor…');
  });
});

describe('desktop notification', () => {
  const capture = () => {
    const out: string[] = [];
    const ran: Array<[string, string[]]> = [];
    return { out, ran, io: { write: (t: string) => out.push(t), run: (c: string, a: string[]) => ran.push([c, a]) } };
  };

  it('uses the protocol of kitty inside kitty', () => {
    const { out, ran, io } = capture();
    expect(sendDesktopNotification('◈ Chat', 'Done', { ...io, env: { KITTY_WINDOW_ID: '3' }, platform: 'linux' })).toBe('kitty');
    expect(out.join('')).toBe(kittyNotificationSequence('◈ Chat', 'Done'));
    expect(out.join('')).toContain('◈ Chat');
    expect(out.join('')).toContain('p=body;Done');
    expect(ran).toEqual([]);
  });

  it('uses notify-send on Linux and osascript on macOS, with the text escaped', () => {
    let c = capture();
    expect(sendDesktopNotification('T', 'B', { ...c.io, env: {}, platform: 'linux' })).toBe('notify-send');
    expect(c.ran).toEqual([['notify-send', ['--app-name=devx', 'T', 'B']]]);
    c = capture();
    expect(sendDesktopNotification('T "x"', 'say "hi"', { ...c.io, env: {}, platform: 'darwin' })).toBe('osascript');
    expect(c.ran[0][0]).toBe('osascript');
    expect(c.ran[0][1][1]).toBe('display notification "say \\"hi\\"" with title "T \\"x\\""');
  });

  it('does nothing where it does not know how, and never throws', () => {
    const c = capture();
    expect(sendDesktopNotification('T', 'B', { ...c.io, env: {}, platform: 'win32' })).toBe('none');
    expect(sendDesktopNotification('T', 'B', { env: {}, platform: 'linux', run: () => { throw new Error('x'); } })).toBe('none');
  });
});

describe('generateChatTitle time limit', () => {
  it('cuts the request off when the time is up, so a slow model does not go on and bill for it', async () => {
    let signal: AbortSignal | undefined;
    const provider = {
      chat: (request: { signal?: AbortSignal }) => {
        signal = request.signal;
        return new Promise<never>((_, reject) => request.signal?.addEventListener('abort', () => reject(new Error('aborted'))));
      },
    };
    expect(await generateChatTitle(provider, 'hi', 'hello', 30)).toBe('');
    expect(signal?.aborted).toBe(true);
  });

  it('leaves the request alone when the answer comes in time', async () => {
    let signal: AbortSignal | undefined;
    const provider = {
      chat: async (request: { signal?: AbortSignal }) => {
        signal = request.signal;
        return { content: 'Fix the build' };
      },
    };
    expect(await generateChatTitle(provider, 'hi', 'hello', 1000)).toBe('Fix the build');
    expect(signal?.aborted).toBe(false);
  });
});
