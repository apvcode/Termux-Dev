export interface MCPServerConfig {
  /** A local server: the program to start (with `args` and `env`). */
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  /** A remote server over HTTP (with `headers`); used instead of `command`. */
  url?: string;
  headers?: Record<string, string>;
  disabled?: boolean;
}

export interface MCPServersConfig {
  mcpServers?: Record<string, MCPServerConfig>;
}

export interface JSONRPCRequest {
  jsonrpc: '2.0';
  id: number | string;
  method: string;
  params?: any;
}

export interface JSONRPCNotification {
  jsonrpc: '2.0';
  method: string;
  params?: any;
}

export interface JSONRPCResponse {
  jsonrpc: '2.0';
  id: number | string;
  result?: any;
  error?: {
    code: number;
    message: string;
    data?: any;
  };
}

/** Hints a server may give about a tool (MCP `annotations`). */
export interface MCPToolAnnotations {
  title?: string;
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  openWorldHint?: boolean;
}

export interface MCPToolDefinition {
  name: string;
  description?: string;
  annotations?: MCPToolAnnotations;
  inputSchema: {
    type: 'object';
    properties?: Record<string, any>;
    required?: string[];
  };
}

export interface MCPCallToolResult {
  content: Array<{
    type: 'text' | 'image' | 'resource';
    text?: string;
    data?: string;
    mimeType?: string;
  }>;
  isError?: boolean;
}

export interface MCPToolInfo {
  name: string;
  description: string;
  readOnly: boolean;
  destructive: boolean;
  openWorld: boolean;
  /** Parameter names with their type and whether they are required. */
  params: Array<{ name: string; type: string; required: boolean; description: string }>;
}

export interface MCPServerStatus {
  name: string;
  command: string;
  status: 'connected' | 'connecting' | 'failed' | 'disabled';
  toolsCount: number;
  tools: string[];
  error?: string;
  /** Where the server is declared: `~/.devxrc.json`, `.devx/mcp.json`, ... */
  source?: string;
  /** `user` servers are the user's own, `project` ones come from the repository. */
  scope?: 'user' | 'project';
  /** What the server says it can do: tools, resources, prompts, ... */
  capabilities?: string[];
  toolInfo?: MCPToolInfo[];
}
