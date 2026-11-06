import { describe, expect, it } from 'vitest';
import { cloneConfig, mergeConfigChanges, sameValue } from '../../src/core/config-merge.js';

describe('mergeConfigChanges', () => {
  it('keeps what another process changed when this one changed something else', () => {
    const base = { model: 'kimi', theme: 'a' };
    const mine = { model: 'kimi', theme: 'b' };              // this process: only the theme
    const disk = { model: 'deepseek', theme: 'a' };          // the other one saved its model meanwhile
    expect(mergeConfigChanges(base, mine, disk)).toEqual({ model: 'deepseek', theme: 'b' });
  });

  it('writes the model only when this process changed it', () => {
    const base = { model: 'kimi' };
    expect(mergeConfigChanges(base, { model: 'kimi-2' }, { model: 'deepseek' })).toEqual({ model: 'kimi-2' });
  });

  it('an override from -m (already in the baseline) is not saved by an unrelated change', () => {
    const base = { model: 'kimi', theme: 'a' };              // baseline taken after -m kimi
    const disk = { model: 'deepseek', theme: 'a' };
    expect(mergeConfigChanges(base, { model: 'kimi', theme: 'c' }, disk).model).toBe('deepseek');
  });

  it('merges objects key by key (API keys of different providers)', () => {
    const base = { apiKeys: { a: '1' } };
    const mine = { apiKeys: { a: '1', b: '2' } };
    const disk = { apiKeys: { a: '1', c: '3' } };
    expect(mergeConfigChanges(base, mine, disk)).toEqual({ apiKeys: { a: '1', b: '2', c: '3' } });
  });

  it('adds and removes entries of a list without dropping the ones added elsewhere', () => {
    const base = { bashAllowlist: ['npm test', 'git status'] };
    const mine = { bashAllowlist: ['npm test', 'ls'] };      // removed git status, added ls
    const disk = { bashAllowlist: ['npm test', 'git status', 'pwd'] };
    expect(mergeConfigChanges(base, mine, disk).bashAllowlist).toEqual(['npm test', 'pwd', 'ls']);
  });

  it('an emptied list is emptied for good', () => {
    const base = { bashAllowlist: ['a'] };
    expect(mergeConfigChanges(base, { bashAllowlist: [] }, { bashAllowlist: ['a', 'b'] }).bashAllowlist).toEqual([]);
  });

  it('removes a key this process deleted and leaves unknown keys alone', () => {
    const base = { a: 1, b: 2 };
    expect(mergeConfigChanges(base, { a: 1 }, { a: 1, b: 2, future: true })).toEqual({ a: 1, future: true });
  });

  it('does not change its inputs', () => {
    const base = { x: { y: 1 } }; const mine = { x: { y: 2 } }; const disk = { x: { y: 1 } };
    const b = cloneConfig(base); const m = cloneConfig(mine); const d = cloneConfig(disk);
    mergeConfigChanges(base, mine, disk);
    expect([base, mine, disk]).toEqual([b, m, d]);
  });

  it('compares values structurally', () => {
    expect(sameValue({ a: [1, { b: 2 }] }, { a: [1, { b: 2 }] })).toBe(true);
    expect(sameValue({ a: 1 }, { a: 1, b: undefined })).toBe(true);
    expect(sameValue([1, 2], [2, 1])).toBe(false);
  });
});
