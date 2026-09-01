import { describe, it, expect } from 'vitest';
import { PromptHistoryNavigator } from '../../src/cli/history_search.js';

describe('PromptHistoryNavigator', () => {
  it('adds unique prompts and ignores consecutive duplicates', () => {
    const nav = new PromptHistoryNavigator();
    nav.add('prompt 1');
    nav.add('prompt 1');
    nav.add('prompt 2');

    expect(nav.getHistory()).toEqual(['prompt 1', 'prompt 2']);
  });

  it('navigates up and down through history', () => {
    const nav = new PromptHistoryNavigator(['first', 'second', 'third']);

    const up1 = nav.navigateUp('');
    expect(up1).toBe('third');

    const up2 = nav.navigateUp('');
    expect(up2).toBe('second');

    const down1 = nav.navigateDown();
    expect(down1).toBe('third');

    const down2 = nav.navigateDown();
    expect(down2).toBe(''); // Restores empty initial draft
  });

  it('navigates linearly when fuzzy is false (default)', () => {
    const nav = new PromptHistoryNavigator(['first foo', 'second bar', 'third foo'], false);

    const up1 = nav.navigateUp('foo');
    expect(up1).toBe('third foo');

    const up2 = nav.navigateUp('foo');
    expect(up2).toBe('second bar'); // Linear does not skip 'second bar'
  });

  it('filters history with query when fuzzy is true', () => {
    const nav = new PromptHistoryNavigator(['fix bug in loop', 'add feature', 'fix typo'], true);

    const match1 = nav.navigateUp('fix');
    expect(match1).toBe('fix typo');

    const match2 = nav.navigateUp('fix');
    expect(match2).toBe('fix bug in loop');
  });

  it('loads persistent history safely without throwing', async () => {
    const history = await PromptHistoryNavigator.loadPersistent();
    expect(Array.isArray(history)).toBe(true);
  });
});
