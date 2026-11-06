import { describe, it, expect } from 'vitest';
import { ToolGuard, sanitizeSchema } from '../../src/providers/toolguard.js';
import { mcpToolName } from '../../src/mcp/manager.js';

const tool = (name: string, parameters: any = { type: 'object', properties: {} }) => ({ name, description: name, parameters });

describe('sanitizeSchema', () => {
  it('drops extras and keeps the shape', () => {
    const out = sanitizeSchema({ $schema: 'x', type: 'object', additionalProperties: false, required: [], properties: { a: { type: 'string', default: 1, description: 'use ${TOKEN}' } } });
    expect(out.$schema).toBeUndefined();
    expect(out.additionalProperties).toBeUndefined();
    expect(out.required).toBeUndefined();
    expect(out.properties.a.default).toBeUndefined();
    expect(out.properties.a.description).not.toContain('${');
  });
});

describe('ToolGuard', () => {
  it('finds and switches off the refused tool', async () => {
    const guard = new ToolGuard();
    const tools = ['a', 'b', 'bad', 'c', 'd'].map((n) => tool(n));
    const probe = async (list: any[]) => !list.some((t) => t.name === 'bad');
    const r = await guard.repair(tools, probe);
    expect(r.changed).toBe(true);
    expect(guard.switchedOff).toEqual(['bad']);
    expect(guard.apply(tools)!.map((t) => t.name)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('does nothing when the request fails without tools too', async () => {
    const guard = new ToolGuard();
    const r = await guard.repair([tool('a')], async () => false);
    expect(r.changed).toBe(false);
  });
});

describe('mcpToolName', () => {
  it('keeps only characters providers accept', () => {
    expect(mcpToolName('my server', 'get.item')).toBe('mcp__my_server__get_item');
    expect(mcpToolName('нота', 'тест')).toMatch(/^[A-Za-z0-9_-]+$/);
  });
  it('stays within 64 characters and stays stable', () => {
    const n = mcpToolName('s', 'x'.repeat(100));
    expect(n.length).toBeLessThanOrEqual(64);
    expect(mcpToolName('s', 'x'.repeat(100))).toBe(n);
  });
});
