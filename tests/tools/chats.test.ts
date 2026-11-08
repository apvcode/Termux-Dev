import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';

let home: string;
let dir: string;

async function chat(id: string, title: string, messages: Array<[string, string]>, extra: Record<string, unknown> = {}, updatedAt = Date.now()) {
  const session = {
    id, title, createdAt: updatedAt - 1000, updatedAt, model: 'm', planMode: false, totalCost: 0,
    messages: [{ role: 'system', content: 'SYSTEM PROMPT SECRET-WORDS' }, ...messages.map(([role, content]) => ({ role, content }))],
    ...extra,
  };
  await fs.writeFile(path.join(dir, `${id}.json`), JSON.stringify(session));
}

async function load() {
  vi.resetModules();
  const tool = (await import('../../src/tools/chats.js')).searchChatsTool;
  const search = await import('../../src/core/chat-search.js');
  const { SessionManager } = await import('../../src/core/session.js');
  return { tool, search, SessionManager };
}

beforeEach(async () => {
  home = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-chats-'));
  dir = path.join(home, '.devx', 'sessions');
  await fs.mkdir(dir, { recursive: true });
  vi.stubEnv('HOME', home);
  vi.stubEnv('USERPROFILE', home);
});
afterEach(async () => {
  vi.unstubAllEnvs();
  await fs.rm(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
});

describe('search_chats', () => {
  it('says so when no chat is saved', async () => {
    const { tool } = await load();
    expect(await tool.execute({ query: 'parser' })).toBe('No earlier chats are saved.');
  });

  it('finds a chat by its words, shows where, and marks the text as data', async () => {
    await chat('sess_1_aaaaa', 'Parser rewrite', [
      ['user', 'we need to rewrite the tokenizer for the config parser'],
      ['assistant', 'Decision: the parser will use a hand written tokenizer, not a regex.'],
    ]);
    await chat('sess_2_bbbbb', 'Weather bot', [['user', 'make a telegram bot that tells the weather']]);
    const { tool } = await load();
    const out = await tool.execute({ query: 'tokenizer decision' });
    expect(out).toContain('data from earlier conversations');
    expect(out).toContain('#aaaaa');
    expect(out).toContain('"Parser rewrite"');
    expect(out).toContain('#2 assistant: Decision: the parser will use a hand written tokenizer');
    expect(out).toContain('read: id "aaaaa"');
    expect(out).not.toContain('Weather bot');
  });

  it('matches other forms of a word, also in Russian', async () => {
    await chat('sess_1_aaaaa', 'Страницы', [
      ['user', 'Давай создадим страницу настроек для бота'],
      ['assistant', 'Хорошо, страница настроек будет в файле settings.ts'],
    ]);
    const { tool } = await load();
    expect(await tool.execute({ query: 'создать страницы настроек' })).toContain('#aaaaa');
    expect(await tool.execute({ query: 'page' })).toContain('No earlier chat matches');
  });

  it('does not search the system prompt or tool output', async () => {
    await chat('sess_1_aaaaa', 'Untitled', [
      ['user', 'run the tests'],
      ['tool', 'HUGE-OUTPUT-MARKER 4000 lines'],
    ]);
    const { tool } = await load();
    expect(await tool.execute({ query: 'SECRET-WORDS' })).toContain('No earlier chat matches');
    expect(await tool.execute({ query: 'HUGE-OUTPUT-MARKER' })).toContain('No earlier chat matches');
  });

  it('leaves out the chat that is open, and can keep to the current folder', async () => {
    await chat('sess_1_aaaaa', 'Old', [['user', 'the migration plan for postgres']], { cwd: '/some/where/else' });
    await chat('sess_2_bbbbb', 'Open', [['user', 'the migration plan for postgres again']], { cwd: process.cwd() });
    const { tool, SessionManager } = await load();
    SessionManager.activeId = 'sess_2_bbbbb';
    const all = await tool.execute({ query: 'migration postgres' });
    expect(all).toContain('#aaaaa');
    expect(all).not.toContain('#bbbbb');
    SessionManager.activeId = '';
    const here = await tool.execute({ query: 'migration postgres', this_project: true });
    expect(here).toContain('#bbbbb');
    expect(here).not.toContain('#aaaaa');
  });

  it('wants at least half of the words in a chat, so a single stray word does not match', async () => {
    await chat('sess_1_aaaaa', 'One', [['user', 'docker is slow']], {}, 1000);
    await chat('sess_2_bbbbb', 'Two', [['user', 'docker compose file for redis and postgres']], {}, 500);
    const { tool } = await load();
    const out = await tool.execute({ query: 'docker compose redis' });
    expect(out.indexOf('#bbbbb')).toBeGreaterThan(-1);
    expect(out).not.toContain('#aaaaa');
  });

  it('lists the recent chats without a query, the newest first', async () => {
    await chat('sess_1_aaaaa', 'Older', [['user', 'hello']], {}, 1000);
    await chat('sess_2_bbbbb', 'Newer', [['user', 'hello']], {}, 2000);
    const { tool } = await load();
    const out = await tool.execute({});
    expect(out.startsWith('Recent chats:')).toBe(true);
    expect(out.indexOf('Newer')).toBeLessThan(out.indexOf('Older'));
  });

  it('reads a chat from a message on, by the short id, with tool calls and cut tool output', async () => {
    await fs.writeFile(path.join(dir, 'sess_1_aaaaa.json'), JSON.stringify({
      id: 'sess_1_aaaaa', title: 'Reading', createdAt: 1, updatedAt: 2, model: 'm', planMode: false, totalCost: 0,
      messages: [
        { role: 'system', content: 'sys' },
        { role: 'user', content: 'list the files' },
        { role: 'assistant', content: '', tool_calls: [{ id: 'x', name: 'list_dir', arguments: {} }] },
        { role: 'tool', name: 'list_dir', content: 'a.ts\n' + 'b'.repeat(500) },
        { role: 'assistant', content: 'There are two files.' },
      ],
    }));
    const { tool } = await load();
    const out = await tool.execute({ id: '#aaaaa' });
    expect(out).toContain('#1 user: list the files');
    expect(out).toContain('#2 assistant:  [calls: list_dir]');
    expect(out).toMatch(/#3 tool\(list_dir\): a\.ts b+/);
    expect(out).not.toContain('b'.repeat(200));
    expect(out).toContain('#4 assistant: There are two files.');
    expect(out).toContain('That is the end of the chat.');
    expect(out).not.toContain('sys');
    const part = await tool.execute({ id: 'sess_1_aaaaa', from: 1, count: 2 });
    expect(part).toContain('More: from=3.');
    expect(await tool.execute({ id: 'nope' })).toContain("No chat 'nope'");
  });

  it('keeps a long chat to a size the model can take', async () => {
    await chat('sess_1_aaaaa', 'Long', Array.from({ length: 60 }, (_, i): [string, string] => [i % 2 ? 'assistant' : 'user', `message ${i} ` + 'x'.repeat(1400)]));
    const { tool } = await load();
    const out = await tool.execute({ id: 'aaaaa', count: 40 });
    expect(out.length).toBeLessThan(13000);
    expect(out).toContain('More: from=');
  });

  it('reads a chat again after it changed', async () => {
    await chat('sess_1_aaaaa', 'Changing', [['user', 'first topic about kubernetes']]);
    const { tool } = await load();
    expect(await tool.execute({ query: 'kubernetes' })).toContain('#aaaaa');
    await new Promise((r) => setTimeout(r, 20));
    await chat('sess_1_aaaaa', 'Changing', [['user', 'now it is about terraform instead']]);
    expect(await tool.execute({ query: 'kubernetes' })).toContain('No earlier chat matches');
    expect(await tool.execute({ query: 'terraform' })).toContain('#aaaaa');
  });

  it('ignores files that are not chats', async () => {
    await fs.writeFile(path.join(dir, 'broken.json'), '{not json');
    await fs.writeFile(path.join(dir, 'other.json'), JSON.stringify({ hello: 1 }));
    await chat('sess_1_aaaaa', 'Fine', [['user', 'something about nginx']]);
    const { tool } = await load();
    expect(await tool.execute({ query: 'nginx' })).toContain('#aaaaa');
  });

  it('needs no approval and is offered in plan mode too', async () => {
    const { CLIConsoleGuard } = await import('../../src/permissions/guard.js');
    expect(new CLIConsoleGuard(false).check('search_chats', { query: 'x' })).toBe(false);
    const { getTools } = await import('../../src/tools/index.js');
    expect(getTools(true).map((t) => t.name)).toContain('search_chats');
  });
});
