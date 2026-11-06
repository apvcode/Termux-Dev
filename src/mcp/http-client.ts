import { MCPServerConfig, MCPToolDefinition, MCPCallToolResult } from './types.js';
import { DEVX_VERSION } from '../core/types.js';

/**
 * A remote MCP server over HTTP ("streamable HTTP": a POST per message, the answer is JSON or an
 * event stream). Configured with `{ "url": "https://…", "headers": { "Authorization": "Bearer ${TOKEN}" } }`;
 * `${NAME}` in a header is taken from the environment, so a token need not be written into the file.
 * A server that needs OAuth cannot be used this way: reach it through `npx -y mcp-remote <url>`.
 */
export class HttpMCPClient {
  public readonly name: string;
  private url: string;
  private headers: Record<string, string>;
  private sessionId: string | null = null;
  private nextId = 1;
  private tools: MCPToolDefinition[] = [];
  private capabilities: string[] = [];
  private connected = false;
  private protocolVersion = '2025-03-26';

  constructor(name: string, config: MCPServerConfig, private fetchImpl: typeof fetch = fetch) {
    this.name = name;
    this.url = String(config.url);
    this.headers = {};
    for (const [key, value] of Object.entries(config.headers ?? {})) {
      this.headers[key] = String(value).replace(/\$\{(\w+)\}/g, (_m, v: string) => process.env[v] ?? '');
    }
  }

  private async post(message: Record<string, unknown>, timeoutMs: number): Promise<any> {
    const response = await this.fetchImpl(this.url, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        accept: 'application/json, text/event-stream',
        'mcp-protocol-version': this.protocolVersion,
        ...(this.sessionId ? { 'mcp-session-id': this.sessionId } : {}),
        ...this.headers,
      },
      body: JSON.stringify(message),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const session = response.headers.get('mcp-session-id');
    if (session) this.sessionId = session;

    if (response.status === 401 || response.status === 403) {
      throw new Error(`MCP server "${this.name}" refused the request (${response.status}). It needs a token (set "headers") or OAuth: use "npx -y mcp-remote ${this.url}" as the command instead.`);
    }
    if (!response.ok && response.status !== 202) {
      throw new Error(`MCP server "${this.name}" answered ${response.status} ${response.statusText}`.trim());
    }
    if (!('id' in message)) return null;      // a notification: nothing to read

    const type = response.headers.get('content-type') || '';
    if (type.includes('text/event-stream')) return this.readStream(response, message.id as number);
    const body = await response.json();
    return this.unwrap(body);
  }

  private unwrap(body: any): any {
    if (body?.error) throw new Error(`MCP error ${body.error.code}: ${body.error.message}`);
    return body?.result;
  }

  /** Reads the events until the answer to our request arrives. */
  private async readStream(response: Response, id: number): Promise<any> {
    const reader = response.body?.getReader();
    if (!reader) throw new Error(`MCP server "${this.name}" sent an empty stream`);
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let end: number;
        while ((end = buffer.search(/\r?\n\r?\n/)) !== -1) {
          const event = buffer.slice(0, end);
          buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '');
          const data = event.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).replace(/^ /, '')).join('\n');
          if (!data) continue;
          try {
            const msg = JSON.parse(data);
            if (msg && msg.id === id && ('result' in msg || 'error' in msg)) return this.unwrap(msg);
          } catch {}
        }
      }
    } finally {
      try { await reader.cancel(); } catch {}
    }
    throw new Error(`MCP server "${this.name}" ended the stream without an answer`);
  }

  private request(method: string, params: unknown, timeoutMs = 15000): Promise<any> {
    return this.post({ jsonrpc: '2.0', id: this.nextId++, method, params }, timeoutMs);
  }

  async start(): Promise<MCPToolDefinition[]> {
    try {
      const init = await this.request('initialize', {
        protocolVersion: this.protocolVersion,
        capabilities: {},
        clientInfo: { name: 'devx', version: DEVX_VERSION },
      });
      if (!init) throw new Error(`Invalid initialize response from MCP server "${this.name}".`);
      if (typeof init.protocolVersion === 'string') this.protocolVersion = init.protocolVersion;
      this.capabilities = Object.keys(init.capabilities || {});
      await this.post({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} }, 15000);

      const tools: MCPToolDefinition[] = [];
      let cursor: string | undefined;
      for (let page = 0; page < 20; page++) {
        const res = await this.request('tools/list', cursor ? { cursor } : {});
        tools.push(...(res?.tools || []));
        cursor = res?.nextCursor;
        if (!cursor) break;
      }
      this.tools = tools;
      this.connected = true;
      return tools;
    } catch (err: any) {
      this.close();
      if (err?.name === 'TimeoutError' || err?.name === 'AbortError') throw new Error(`MCP server "${this.name}" did not answer in time.`);
      throw err;
    }
  }

  getTools(): MCPToolDefinition[] {
    return this.tools;
  }

  getCapabilities(): string[] {
    return this.capabilities;
  }

  hasConnected(): boolean {
    return this.connected;
  }

  async callTool(toolName: string, args: any): Promise<string> {
    if (!this.connected) throw new Error(`MCP server "${this.name}" is not connected.`);
    const res: MCPCallToolResult = await this.request('tools/call', { name: toolName, arguments: args || {} }, 60000);
    if (!res || !res.content) return JSON.stringify(res || {});
    const parts: string[] = [];
    for (const c of res.content) {
      if (c.type === 'text' && c.text) parts.push(c.text);
      else if (c.type === 'image' && c.data) parts.push(`[Image content (${c.mimeType || 'image/png'})]`);
      else if (c.type === 'resource') parts.push(`[Resource: ${JSON.stringify(c)}]`);
    }
    const text = parts.join('\n\n') || JSON.stringify(res);
    if (res.isError) throw new Error(text);
    return text;
  }

  close(): void {
    this.connected = false;
    if (this.sessionId) {
      // The session is ended politely; whether the server cares is its own business.
      const id = this.sessionId;
      this.sessionId = null;
      void this.fetchImpl(this.url, { method: 'DELETE', headers: { 'mcp-session-id': id, ...this.headers }, signal: AbortSignal.timeout(3000) }).catch(() => {});
    }
  }
}
