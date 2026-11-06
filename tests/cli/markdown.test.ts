import { beforeEach, describe, expect, it, vi } from 'vitest';

// Colours on, so the styles can be checked.
vi.hoisted(() => { process.env.FORCE_COLOR = '1'; process.env.DEVX_COLOR = 'truecolor'; });

import { InlineStyler, MarkdownStreamer } from '../../src/cli/markdown.js';
import { setActiveTheme } from '../../src/cli/theme.js';
import { stripAnsi } from '../../src/cli/width.js';

beforeEach(() => setActiveTheme('cyan'));

/** Streams a text the way the model sends it: in pieces of a few characters. */
function stream(text: string, piece = 7): string {
  const out: string[] = [];
  const s = new MarkdownStreamer();
  s.setSink((t) => out.push(t));
  for (let i = 0; i < text.length; i += piece) s.push(text.slice(i, i + piece));
  s.finish();
  return out.join('');
}

const BOLD_ON = '\x1b[1m';
const BOLD_OFF = '\x1b[22m';

describe('bold, italic and code over several words while streaming', () => {
  const sentence = '**Реально пользовательских непереведённых — 78**, вот что заметно в интерфейсе:';

  it('never leaves the marks on the screen, whatever the size of the pieces', () => {
    for (const piece of [1, 2, 3, 5, 7, 11, 40, 500]) {
      const plain = stripAnsi(stream(sentence + '\n', piece));
      expect(plain, `piece ${piece}`).toBe('Реально пользовательских непереведённых — 78, вот что заметно в интерфейсе:\n');
    }
  });

  it('styles the whole span as bold, not just a word of it', () => {
    const out = stream(sentence + '\n', 5);
    // Every word of the span is inside an open bold; the text after the span is not.
    const boldText = [...out.matchAll(/\x1b\[1m([^\x1b]*)\x1b\[22m/g)].map((m) => m[1]).join('');
    expect(boldText).toContain('Реально');
    expect(boldText).toContain('пользовательских');
    expect(boldText).toContain('непереведённых');
    expect(boldText).toContain('78');
    expect(boldText).not.toContain('вот что');
  });

  it('handles italic and code spans with spaces, in lists too', () => {
    const md = '- слово *курсив из трёх слов* и `код с пробелом` дальше\n- **жирный** и *ещё* пункт\n';
    const plain = stripAnsi(stream(md, 4));
    expect(plain).toBe('  • слово курсив из трёх слов и код с пробелом дальше\n  • жирный и ещё пункт\n');
    const out = stream(md, 4);
    expect(out).toContain('\x1b[3m');
    expect(out).toContain(BOLD_ON);
  });

  it('keeps marks that are not marks: multiplication, a lone star, an unclosed span', () => {
    expect(stripAnsi(stream('2 * 3 = 6 and a ** b and snake_case_name\n'))).toBe('2 * 3 = 6 and a ** b and snake_case_name\n');
    expect(stripAnsi(stream('a * b * c\n'))).toBe('a * b * c\n');
    // an unclosed span does not colour the next line
    const out = stream('**unclosed here\nnext line\n');
    const next = out.split('\n')[1];
    expect(next).not.toContain(BOLD_ON);
  });

  it('a nested bullet keeps its indentation; headings and quotes show no marks', () => {
    const plain = stripAnsi(stream('- one\n  - nested **bold**\n## A **heading** with `code`\n> a *quote* here\n'));
    expect(plain).toContain('  • one');
    expect(plain).toContain('    • nested bold');
    expect(plain).toContain('A heading with code');
    expect(plain).toContain('│ a quote here');
    expect(plain).not.toMatch(/\*|`/);
  });

  it('the text of the screenshot comes out clean', () => {
    const md = [
      '**Реально пользовательских непереведённых — 78**, вот что заметно в интерфейсе:',
      '- `verify_select_method_title` — «Подтвердите адрес почты» (экран верификации)',
      '- *dialog_debug_* — там и так английский у всех',
      '',
      'Что дальше — добить эти 78 строк до 100% RU?',
    ].join('\n') + '\n';
    const plain = stripAnsi(stream(md, 6));
    expect(plain).not.toMatch(/\*\*|`|\*/);
    expect(plain).toContain('Реально пользовательских непереведённых — 78, вот что заметно в интерфейсе:');
    expect(plain).toContain('• verify_select_method_title — «Подтвердите адрес почты» (экран верификации)');
    expect(plain).toContain('• dialog_debug_ — там и так английский у всех');
  });
});

describe('InlineStyler', () => {
  it('keeps its state between pieces and forgets it at the end of a line', () => {
    const st = new InlineStyler();
    const a = st.format('start **bold ');
    const b = st.format('still bold** done');
    expect(stripAnsi(a + b)).toBe('start bold still bold done');
    expect(a.endsWith(BOLD_OFF)).toBe(true);             // each piece is closed
    expect(b.startsWith(BOLD_ON)).toBe(true);            // and opened again
    st.format('**open ');
    st.endLine();
    expect(st.format('plain')).toBe('plain');
  });
});

describe('a ```markdown block is shown formatted', () => {
  const md = [
    'Вот пара примеров:',
    '',
    '```markdown',
    '# Релиз v1.2.0',
    '',
    'Основные изменения:',
    '',
    '- Ускорен **поиск** по истории',
    '- Исправлен баг #142',
    '',
    '> Обновление: команда `devx update`.',
    '```',
    '',
    'Могу записать в файл.',
  ].join('\n') + '\n';

  it('streams the content as formatted markdown, without the fence, the frame or the marks', () => {
    for (const piece of [3, 9, 500]) {
      const plain = stripAnsi(stream(md, piece));
      expect(plain, `piece ${piece}`).not.toMatch(/```|\[markdown\]|┌|└|\*\*|`/);
      expect(plain).toContain('Релиз v1.2.0');
      expect(plain).not.toContain('# Релиз');
      expect(plain).toContain('• Ускорен поиск по истории');
      expect(plain).toContain('• Исправлен баг #142');
      expect(plain).toContain('│ Обновление: команда devx update.');
      expect(plain).toContain('Могу записать в файл.');
    }
  });

  it('works for ```md too, and any other language keeps its frame and its source', () => {
    expect(stripAnsi(stream('```md\n## Заголовок\n```\n'))).toContain('Заголовок');
    expect(stripAnsi(stream('```md\n## Заголовок\n```\n'))).not.toContain('##');
    const code = stripAnsi(stream('```ts\nconst a = "**not bold**"; // # not a heading\n```\n'));
    expect(code).toContain('[ts]');
    expect(code).toContain('const a = "**not bold**"; // # not a heading');
  });

  it('text after the block is not affected by it', () => {
    const plain = stripAnsi(stream('```markdown\n- one\n```\nafter **bold**\n'));
    expect(plain).toContain('after bold');
  });

  it('the whole-text renderer drops the fence of markdown blocks too (resumed chats, redraw)', async () => {
    const { renderMarkdown, unfenceMarkdown } = await import('../../src/cli/markdown.js');
    expect(unfenceMarkdown('a\n```markdown\n# H\n- x\n```\nb')).toBe('a\n# H\n- x\nb');
    expect(unfenceMarkdown('```ts\n# keep\n```')).toBe('```ts\n# keep\n```');
    const out = stripAnsi(renderMarkdown('```markdown\n# Релиз\n\n**жирный** и `код`\n```'));
    expect(out).toContain('Релиз');
    expect(out).not.toMatch(/```|\*\*|# Релиз/);
  });
});

describe('lists with `code` and **bold** in their items', () => {
  const text = 'Files:\n\n  * `a.png`\n  * `Notion Backup.txt`\n    * nested **bold** `n`\n\n* `x`\n';

  it('drawn at once (a redrawn chat): bullets, and no marks on the screen', async () => {
    const { renderMarkdown } = await import('../../src/cli/markdown.js');
    const out = stripAnsi(renderMarkdown(text));
    expect(out).toContain('• a.png');
    expect(out).toContain('• Notion Backup.txt');
    expect(out).toContain('• nested bold n');
    expect(out).not.toMatch(/[`*]/);
  });

  it.each([1, 2, 7, 1000])('streamed in pieces of %i characters: the same', (piece) => {
    const out = stripAnsi(stream(text, piece));
    expect(out).toContain('• a.png');
    expect(out).toContain('• Notion Backup.txt');
    expect(out).not.toMatch(/[`*]/);
  });

  it('a lone * in prose stays', async () => {
    const { renderMarkdown } = await import('../../src/cli/markdown.js');
    expect(stripAnsi(renderMarkdown('2 * 3 * 4'))).toBe('2 * 3 * 4');
  });
});
