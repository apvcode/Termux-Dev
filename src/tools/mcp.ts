import { Tool } from '../core/types.js';
import { MCPManager, readClaudeServers, saveUserServers } from '../mcp/manager.js';

/**
 * Lets the agent set up MCP servers when the user asks ("connect to Notion over MCP").
 * Adding a server starts a program on the user's machine, so devx always asks first (see
 * the guard), also in YOLO mode. The server is written to the user's own `~/.devx/mcp.json`.
 */
export const mcpManageTool: Tool = {
  name: 'mcp_manage',
  definition: {
    name: 'mcp_manage',
    description:
      "Manage MCP servers of devx. action 'list': the servers with their state, tools and errors. " +
      "action 'add': save a server (name, command, args, env) to ~/.devx/mcp.json and start it; the user is asked to approve. " +
      "action 'import_claude': take the servers the user already has in Claude Code (~/.claude.json). " +
      "The tools of a newly added server can be called from the user's next message on.",
    parameters: {
      type: 'object',
      properties: {
        action: { type: 'string', enum: ['list', 'add', 'import_claude'], description: 'What to do' },
        name: { type: 'string', description: "add: short server name, letters, digits, '-' and '_' (it becomes part of the tool names)" },
        command: { type: 'string', description: "add: the program to start, e.g. 'npx', 'uvx', or a path to a binary (for a local server)" },
        url: { type: 'string', description: 'add: the address of a remote MCP server over HTTP, instead of command. Put a token in headers; a server that needs OAuth is reached with command npx and args ["-y","mcp-remote","<url>"] instead.' },
        headers: { type: 'object', description: 'add (with url): HTTP headers, e.g. {"Authorization": "Bearer ${TOKEN}"}: ${NAME} is read from the environment' },
        args: { type: 'array', items: { type: 'string' }, description: "add: arguments, e.g. ['-y', 'mcp-remote', 'https://mcp.notion.com/mcp']" },
        env: { type: 'object', description: 'add: environment variables the server needs (tokens go here, never into args)' },
      },
      required: ['action'],
    },
  },
  validateArgs(args: any) {
    if (!args || !['list', 'add', 'import_claude'].includes(args.action)) throw new Error("action must be 'list', 'add' or 'import_claude'");
    if (args.action === 'add') {
      if (!args.name || !/^[A-Za-z0-9_-]{1,40}$/.test(args.name)) throw new Error("name is required: letters, digits, '-' and '_', up to 40 characters");
      if (args.url !== undefined) {
        if (typeof args.url !== 'string' || !/^https?:\/\//i.test(args.url)) throw new Error('url must start with http:// or https://');
      } else if (!args.command || typeof args.command !== 'string') {
        throw new Error('command (or url) is required');
      }
      if (args.args !== undefined && !Array.isArray(args.args)) throw new Error('args must be an array of strings');
    }
  },
  async execute(args: any) {
    const manager = MCPManager.getInstance();
    if (args.action === 'add') {
      saveUserServers({
        [args.name]: args.url
          ? { url: args.url, ...(args.headers && typeof args.headers === 'object' ? { headers: args.headers } : {}) }
          : { command: args.command, ...(args.args?.length ? { args: args.args.map(String) } : {}), ...(args.env && typeof args.env === 'object' ? { env: args.env } : {}) },
      }, true);
      await manager.reload();
    } else if (args.action === 'import_claude') {
      const found = readClaudeServers();
      if (Object.keys(found).length === 0) return 'No MCP servers found in ~/.claude.json.';
      saveUserServers(found);
      await manager.reload();
    }
    const rows = manager.getStatuses().map((s) => ({
      name: s.name,
      status: s.status,
      tools: s.toolsCount,
      ...(s.error ? { error: s.error } : {}),
      ...(s.status === 'connected' ? { sample_tools: s.tools.slice(0, 8) } : {}),
    }));
    return JSON.stringify({ servers: rows, note: rows.length === 0 ? 'No MCP servers configured yet.' : 'New tools can be called from the next user message.' });
  },
};
