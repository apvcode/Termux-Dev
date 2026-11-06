import { ToolDefinition } from '../core/types.js';

/**
 * A gateway can refuse the tool definitions of a request (a strict schema check of its own), and one
 * odd definition from an MCP server then breaks every message. When a request is refused and it works
 * without tools, the tools are looked at: first their schemas are simplified, and if that is not
 * enough the ones that are refused are found (a halving search) and switched off for the session.
 */

const DROPPED_KEYS = new Set(['$schema', '$id', 'additionalProperties', 'default', 'examples', 'format', 'title']);

/** A plainer copy of a JSON schema: only what every gateway accepts. */
export function sanitizeSchema(node: any): any {
  if (Array.isArray(node)) return node.map(sanitizeSchema);
  if (!node || typeof node !== 'object') return node;
  const out: Record<string, any> = {};
  for (const [key, value] of Object.entries(node)) {
    if (DROPPED_KEYS.has(key)) continue;
    if (key === 'description' && typeof value === 'string') {
      out[key] = value.replace(/\$\{/g, '$ {').slice(0, 1000);
      continue;
    }
    if (key === 'required' && Array.isArray(value) && value.length === 0) continue;
    // A property called "title" is a property, not the keyword: only schema nodes are cleaned like that.
    out[key] = key === 'properties' && value && typeof value === 'object'
      ? Object.fromEntries(Object.entries(value as Record<string, any>).map(([name, schema]) => [name, sanitizeSchema(schema)]))
      : sanitizeSchema(value);
  }
  if (out.type === 'object' && !out.properties) out.properties = {};
  return out;
}

export function sanitizeTool(tool: ToolDefinition): ToolDefinition {
  return {
    ...tool,
    description: String(tool.description ?? '').replace(/\$\{/g, '$ {').slice(0, 1000),
    parameters: sanitizeSchema(tool.parameters ?? { type: 'object', properties: {} }),
  };
}

export type ToolProbe = (tools: ToolDefinition[]) => Promise<boolean>;

export interface ToolRepair {
  /** Something was changed: the request is worth sending again. */
  changed: boolean;
  /** What to tell the user. */
  notice?: string;
}

export class ToolGuard {
  private sanitize = false;
  private disabled = new Set<string>();

  /** The tools as they are sent now: the switched-off ones left out, the schemas simplified once that was needed. */
  apply(tools: ToolDefinition[] | undefined): ToolDefinition[] | undefined {
    if (!tools) return tools;
    const kept = tools.filter((t) => !this.disabled.has(t.name));
    return this.sanitize ? kept.map(sanitizeTool) : kept;
  }

  get switchedOff(): string[] {
    return [...this.disabled];
  }

  private async findOne(list: ToolDefinition[], probe: ToolProbe): Promise<string | null> {
    if (list.length === 0) return null;
    if (list.length === 1) return list[0].name;
    const mid = Math.ceil(list.length / 2);
    const left = list.slice(0, mid);
    const right = list.slice(mid);
    if (!(await probe(left))) return this.findOne(left, probe);
    if (!(await probe(right))) return this.findOne(right, probe);
    return null;     // each half is accepted alone: the trouble is in how they go together
  }

  /**
   * `probe` sends the request with the given tools (and nothing that costs tokens) and says whether it was accepted.
   * Returns whether the tools were changed and what to tell the user.
   */
  async repair(tools: ToolDefinition[] | undefined, probe: ToolProbe): Promise<ToolRepair> {
    if (!tools || tools.length === 0) return { changed: false };
    // Without tools the request has to work, or the tools are not what is wrong.
    if (!(await probe([]))) return { changed: false };

    if (!this.sanitize) {
      this.sanitize = true;
      if (await probe(this.apply(tools)!)) {
        return { changed: true, notice: 'The endpoint refused the tool definitions. They were simplified (schema extras removed) and the request works now.' };
      }
    }

    const off: string[] = [];
    for (let round = 0; round < 6; round++) {
      const current = this.apply(tools)!;
      if (current.length === 0 || (await probe(current))) break;
      const bad = await this.findOne(current, probe);
      if (!bad) break;
      this.disabled.add(bad);
      off.push(bad);
    }
    if (off.length === 0) return { changed: this.sanitize };
    return {
      changed: true,
      notice: `The endpoint refused ${off.length === 1 ? 'the tool' : 'the tools'} ${off.join(', ')}. ${off.length === 1 ? 'It is' : 'They are'} switched off for this session; the others work.`,
    };
  }
}
