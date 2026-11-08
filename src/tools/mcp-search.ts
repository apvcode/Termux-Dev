import { Tool, ToolDefinition } from '../core/types.js';
import { MCPManager, describeTool } from '../mcp/manager.js';

const DEFAULT_LIMIT = 5;
const MAX_LIMIT = 10;
const MAX_NAMES = 20;
const MAX_LISTED = 100;
/** How many tool names of a server the description shows (the agent gets a feeling for what is inside). */
const NAMES_SHOWN = 10;

function catalogText(manager: MCPManager): string {
  return manager
    .catalog()
    .map((s) => {
      const state = !s.lazy ? 'tools already loaded' : s.loaded > 0 ? `${s.loaded} of ${s.total} loaded` : 'not loaded';
      const names = s.lazy ? ` - ${s.tools.slice(0, NAMES_SHOWN).join(', ')}${s.tools.length > NAMES_SHOWN ? `, +${s.tools.length - NAMES_SHOWN} more` : ''}` : '';
      return `- ${s.name} (${s.total} tools, ${state})${names}`;
    })
    .join('\n');
}

/**
 * `mcp_search`: the agent's way into the MCP servers whose tools are not sent with every request (they
 * would cost tens of thousands of tokens). The servers are listed in the description, so the agent knows
 * what exists; it loads what the task needs and calls it from its next step. Only offered while some
 * server is lazy.
 */
export function createMcpSearchTool(manager: MCPManager = MCPManager.getInstance()): Tool {
  return {
    name: 'mcp_search',
    get definition(): ToolDefinition {
      return {
        name: 'mcp_search',
        description:
          'Find and load tools of connected MCP servers. The tools of these servers are not loaded to save context: load the ones a task needs here, then call them like any other tool (from your next step on). ' +
          "Use 'query' with a few English keywords (tool descriptions are in English; e.g. \"create page\", \"search issues\") to load the best matches, 'names' to load tools you already know, or 'server' alone to list the tools of one server. Never guess a tool name or its arguments.\n" +
          `Servers:\n${catalogText(manager)}`,
        parameters: {
          type: 'object',
          properties: {
            query: { type: 'string', description: 'A few keywords for what you need; the best matches are loaded' },
            server: { type: 'string', description: 'Limit the search to this server; alone it lists the tools of the server' },
            names: { type: 'array', items: { type: 'string' }, description: `Tools to load by name (full name, or the server's own name for it), up to ${MAX_NAMES}` },
            limit: { type: 'integer', description: `With query: how many matches to load (default ${DEFAULT_LIMIT}, at most ${MAX_LIMIT})` },
          },
        },
      };
    },
    validateArgs(args: any) {
      if (args === null || typeof args !== 'object') throw new Error('arguments must be an object');
      if (args.query !== undefined && typeof args.query !== 'string') throw new Error('query must be a string');
      if (args.server !== undefined && typeof args.server !== 'string') throw new Error('server must be a string');
      if (args.names !== undefined && (!Array.isArray(args.names) || args.names.some((n: unknown) => typeof n !== 'string'))) throw new Error('names must be an array of strings');
    },
    async execute(args: any) {
      const known = manager.catalog();
      if (known.length === 0) return 'No MCP server is connected.';
      const server: string | undefined = args.server?.trim() || undefined;
      if (server && !known.some((s) => s.name.toLowerCase() === server.toLowerCase())) {
        return `No MCP server '${server}'. Servers: ${known.map((s) => s.name).join(', ')}.`;
      }

      if (Array.isArray(args.names) && args.names.length > 0) {
        const found: Tool[] = [];
        const missing: string[] = [];
        for (const ref of args.names.slice(0, MAX_NAMES) as string[]) {
          const tool = manager.findTool(ref) ?? (server ? manager.findTool(`${server}/${ref}`) : undefined);
          if (tool) found.push(tool);
          else missing.push(ref);
        }
        manager.load(found);
        return [
          found.length ? `Loaded ${found.length} tool${found.length === 1 ? '' : 's'}; call ${found.length === 1 ? 'it' : 'them'} now:\n${found.map(describeTool).join('\n')}` : 'Nothing was loaded.',
          missing.length ? `Not found: ${missing.join(', ')}. Use 'query' to search.` : '',
        ].filter(Boolean).join('\n');
      }

      const query = args.query?.trim();
      if (query) {
        const limit = Math.min(MAX_LIMIT, Math.max(1, Math.floor(Number(args.limit)) || DEFAULT_LIMIT));
        const ranked = manager.search(query, server);
        if (ranked.length === 0) {
          return `No tool matches "${query}". Try other keywords, or list a server with 'server'.`;
        }
        const top = ranked.slice(0, limit);
        manager.load(top);
        const more = ranked.slice(limit, limit + 10);
        return [
          `Loaded ${top.length} tool${top.length === 1 ? '' : 's'}; call ${top.length === 1 ? 'it' : 'them'} now:\n${top.map(describeTool).join('\n')}`,
          more.length ? `Other matches (not loaded; load with 'names'): ${more.map((t) => t.name).join(', ')}` : '',
        ].filter(Boolean).join('\n');
      }

      if (server) {
        const tools = manager.toolsOf(server);
        const rows = tools.slice(0, MAX_LISTED).map((t) => {
          const desc = t.definition.description.replace(/^\[MCP: [^\]]*\]\s*/, '').replace(/\s+/g, ' ').trim();
          return `- ${t.name}${manager.isLoaded(t) ? ' (loaded)' : ''}: ${desc.length > 90 ? desc.slice(0, 87) + '...' : desc}`;
        });
        return `${tools.length} tools of ${server}${tools.length > MAX_LISTED ? ` (first ${MAX_LISTED})` : ''}. Load the ones you need with 'names' or 'query':\n${rows.join('\n')}`;
      }

      return `MCP servers:\n${catalogText(manager)}\nGive 'query' (keywords), 'names' or 'server'.`;
    },
  };
}
