import { describe, expect, it } from 'vitest';
import { dropdownPageSize, renderDropdown } from '../../src/cli/dropdown.js';
import { SLASH_COMMANDS } from '../../src/cli/prompt.js';
import { stripAnsi } from '../../src/cli/width.js';

const rows = SLASH_COMMANDS.map((c) => ({ label: c.cmd, desc: c.desc }));
const plain = (lines: string[]) => lines.map(stripAnsi);

describe('the list under the prompt', () => {
  it('has a page of at most ten rows and fits a small screen', () => {
    expect(dropdownPageSize(50)).toBe(10);
    expect(dropdownPageSize(24)).toBe(10);
    expect(dropdownPageSize(14)).toBe(5);
    expect(dropdownPageSize(5)).toBe(3);
  });

  it('shows a page and where in the list the mark is', () => {
    const lines = plain(renderDropdown(rows, 0, 80, 40));
    expect(lines).toHaveLength(10);
    expect(lines[0]).toContain('› /new');
    expect(lines[9]).toContain(`1 / ${rows.length}`);
  });

  it('scrolls to every item: the last command is reached', () => {
    const lines = plain(renderDropdown(rows, rows.length - 1, 80, 40));
    expect(lines.join('\n')).toContain('› /exit');
    expect(lines[lines.length - 1]).toContain(`${rows.length} / ${rows.length}`);
    // no command is lost on the way down
    const seen = new Set<string>();
    for (let i = 0; i < rows.length; i++) {
      for (const l of plain(renderDropdown(rows, i, 80, 40))) {
        const m = /^[› ] (\/\S+(?: del)?)/.exec(l);
        if (m) seen.add(m[1]);
      }
    }
    expect(seen.size).toBe(rows.length);
  });

  it('a short list has no position row', () => {
    const lines = plain(renderDropdown(rows.slice(0, 4), 1, 80, 40));
    expect(lines).toHaveLength(4);
    expect(lines.join('')).not.toContain(' / 4');
  });

  it('is empty for nothing', () => {
    expect(renderDropdown([], 0, 80, 40)).toEqual([]);
  });
});
