import { describe, it, expect } from 'vitest';
import { formatDiffBox } from '../../src/cli/diff.js';

describe('Diff Formatter', () => {
  it('formats diff with line numbers and file header', () => {
    const diffLines = [
      '1    const a = 1;',
      '2 -  const b = 2;',
      '2 +  const b = 42;',
      '3    const c = 3;'
    ];
    const box = formatDiffBox('src/index.ts', diffLines, 60, 'Edited 1 line');
    expect(box).toContain('index.ts');
    expect(box).toContain('const b = 42;');
    expect(box).toContain('Edited 1 line');
  });

  it('contains NO ANSI background color escape codes (preventing v1.4.13 background leak)', () => {
    const diffLines = [
      '10 -  oldCode();',
      '10 +  newCode();'
    ];
    const boxColored = formatDiffBox('test.ts', diffLines, 80, undefined, true);
    const boxPlain = formatDiffBox('test.ts', diffLines, 80, undefined, false);

    // Regex checking for ANSI background escape codes:
    // \x1b[40m - \x1b[47m (standard bg)
    // \x1b[100m - \x1b[107m (high-intensity bg)
    // \x1b[48; (256/RGB bg)
    const hasBgAnsiColored = /\x1b\[(4[0-7]|10[0-7]|48;)/.test(boxColored);
    const hasBgAnsiPlain = /\x1b\[(4[0-7]|10[0-7]|48;)/.test(boxPlain);
    expect(hasBgAnsiColored).toBe(false);
    expect(hasBgAnsiPlain).toBe(false);
  });

  it('respects diffColors flag (default false vs true)', () => {
    const diffLines = ['10 +  newFeature();'];
    const colored = formatDiffBox('test.ts', diffLines, 80, undefined, true);
    const plain = formatDiffBox('test.ts', diffLines, 80, undefined, false);

    expect(colored).toContain('newFeature');
    expect(plain).toContain('newFeature');
  });
});
