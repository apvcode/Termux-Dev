import { describe, it, expect } from 'vitest';
import { filterCommandPalette, estimatePromptTokens, formatPromptTokenBadge } from '../../src/cli/prompt_palette.js';

describe('Prompt Palette & Token Counter', () => {
  const commands = [
    { cmd: '/help', desc: 'Show all available commands' },
    { cmd: '/plan', desc: 'Switch to PLAN mode' },
    { cmd: '/agent', desc: 'Switch to AGENT mode' },
    { cmd: '/undo', desc: 'Revert last file changes' }
  ];

  it('filters commands by query and description', () => {
    const all = filterCommandPalette('/', commands);
    expect(all).toHaveLength(4);

    const matchPlan = filterCommandPalette('/pl', commands);
    expect(matchPlan).toHaveLength(1);
    expect(matchPlan[0].cmd).toBe('/plan');

    const matchDesc = filterCommandPalette('revert', commands);
    expect(matchDesc).toHaveLength(1);
    expect(matchDesc[0].cmd).toBe('/undo');
  });

  it('estimates prompt tokens and formats badge', () => {
    const tokens = estimatePromptTokens('This is a test prompt with some code snippet');
    expect(tokens).toBeGreaterThan(5);

    const smallBadge = formatPromptTokenBadge(10, 30);
    expect(smallBadge).toBe('');

    const largeBadge = formatPromptTokenBadge(150, 30);
    expect(largeBadge).toContain('~150 tok');

    const kBadge = formatPromptTokenBadge(2500, 30);
    expect(kBadge).toContain('~2.5k tok');
  });
});
