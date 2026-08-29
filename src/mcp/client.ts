import { spawn, ChildProcess } from 'child_process';
import { MCPServerConfig, MCPToolDefinition, MCPCallToolResult, JSONRPCRequest, JSONRPCResponse } from './types.js';

export class MCPClient {
  public readonly name: string;
  private config: MCPServerConfig;
  private process: ChildProcess | null = null;
  private nextRequestId = 1;
  private pendingRequests = new Map<number | string, {
    resolve: (res: any) => void;
    reject: (err: any) => void;
    timer: NodeJS.Timeout;
  }>();
  private buffer = '';
  private tools: MCPToolDefinition[] = [];
  private isConnected = false;

  constructor(name: string, config: MCPServerConfig) {
    this.name = name;
    this.config = config;
  }

  async start(): Promise<MCPToolDefinition[]> {
    if (this.config.disabled) {
      throw new Error(`MCP server "${this.name}" is disabled in configuration.`);
    }

    return new Promise(async (resolve, reject) => {
      let isSettled = false;
      const initialTimer = setTimeout(() => {
        if (!isSettled) {
          isSettled = true;
          this.close();
          reject(new Error(`MCP server "${this.name}" initialization timed out after 15s.`));
        }
      }, 15000);

      try {
        const env = {
          ...process.env,
          ...(this.config.env || {})
        };

        const isWindows = process.platform === 'win32';
        const useShell = isWindows && (
          this.config.command.endsWith('.cmd') || 
          this.config.command.endsWith('.bat') || 
          this.config.command === 'npx' || 
          this.config.command === 'npm'
        );

        this.process = spawn(this.config.command, this.config.args || [], {
          env,
          stdio: ['pipe', 'pipe', 'pipe'],
          shell: useShell
        });

        this.process.stdout?.on('data', (data) => {
          this.handleStdout(data.toString());
        });

        this.process.stderr?.on('data', (_data) => {
          // Stderr from MCP servers is used for logging/debugging
        });

        this.process.on('error', (err) => {
          if (!isSettled) {
            isSettled = true;
            clearTimeout(initialTimer);
            reject(new Error(`Failed to start MCP server "${this.name}": ${err.message}`));
          }
          this.cleanup();
        });

        this.process.on('close', (_code) => {
          this.cleanup();
        });

        // 1. Initialize Handshake
        const initResult = await this.sendRequest('initialize', {
          protocolVersion: '2024-11-05',
          capabilities: {},
          clientInfo: {
            name: 'devx',
            version: '1.4.9'
          }
        });

        if (!initResult) {
          throw new Error(`Invalid initialize response from MCP server "${this.name}".`);
        }

        // 2. Send initialized notification
        this.sendNotification('notifications/initialized', {});

        // 3. Fetch Tools List
        const toolsResult = await this.sendRequest('tools/list', {});
        this.tools = toolsResult?.tools || [];
        this.isConnected = true;

        if (!isSettled) {
          isSettled = true;
          clearTimeout(initialTimer);
          resolve(this.tools);
        }
      } catch (err: any) {
        if (!isSettled) {
          isSettled = true;
          clearTimeout(initialTimer);
          this.close();
          reject(err);
        }
      }
    });
  }

  getTools(): MCPToolDefinition[] {
    return this.tools;
  }

  hasConnected(): boolean {
    return this.isConnected;
  }

  async callTool(toolName: string, args: any): Promise<string> {
    if (!this.process || !this.isConnected) {
      throw new Error(`MCP server "${this.name}" is not connected.`);
    }

    const res: MCPCallToolResult = await this.sendRequest('tools/call', {
      name: toolName,
      arguments: args || {}
    }, 60000); // 60s timeout for tool calls

    if (!res || !res.content) {
      return JSON.stringify(res || {});
    }

    const outputParts: string[] = [];
    for (const c of res.content) {
      if (c.type === 'text' && c.text) {
        outputParts.push(c.text);
      } else if (c.type === 'image' && c.data) {
        outputParts.push(`[Image content (${c.mimeType || 'image/png'})]`);
      } else if (c.type === 'resource') {
        outputParts.push(`[Resource: ${JSON.stringify(c)}]`);
      }
    }

    const finalResult = outputParts.join('\n\n') || JSON.stringify(res);
    if (res.isError) {
      throw new Error(finalResult);
    }
    return finalResult;
  }

  private handleStdout(chunk: string) {
    this.buffer += chunk;
    const lines = this.buffer.split('\n');
    this.buffer = lines.pop() || '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;

      try {
        const msg = JSON.parse(trimmed);
        if ('id' in msg && msg.id !== undefined) {
          const pending = this.pendingRequests.get(msg.id);
          if (pending) {
            clearTimeout(pending.timer);
            this.pendingRequests.delete(msg.id);
            if (msg.error) {
              pending.reject(new Error(`MCP error ${msg.error.code}: ${msg.error.message}`));
            } else {
              pending.resolve(msg.result);
            }
          }
        }
      } catch {
        // Ignore non-JSON line outputs (e.g. startup banner)
      }
    }
  }

  private sendRequest(method: string, params: any, timeoutMs = 15000): Promise<any> {
    return new Promise((resolve, reject) => {
      if (!this.process || !this.process.stdin) {
        return reject(new Error(`MCP server "${this.name}" process is not running.`));
      }

      const id = this.nextRequestId++;
      const timer = setTimeout(() => {
        if (this.pendingRequests.has(id)) {
          this.pendingRequests.delete(id);
          reject(new Error(`MCP request "${method}" to server "${this.name}" timed out (${timeoutMs / 1000}s).`));
        }
      }, timeoutMs);

      this.pendingRequests.set(id, { resolve, reject, timer });

      const request: JSONRPCRequest = {
        jsonrpc: '2.0',
        id,
        method,
        params
      };

      try {
        this.process.stdin.write(JSON.stringify(request) + '\n');
      } catch (err: any) {
        clearTimeout(timer);
        this.pendingRequests.delete(id);
        reject(new Error(`Failed to write to MCP server "${this.name}": ${err.message}`));
      }
    });
  }

  private sendNotification(method: string, params: any) {
    if (!this.process || !this.process.stdin) return;
    const notif = {
      jsonrpc: '2.0',
      method,
      params
    };
    try {
      this.process.stdin.write(JSON.stringify(notif) + '\n');
    } catch {}
  }

  close() {
    this.cleanup();
    if (this.process) {
      try {
        this.process.kill();
      } catch {}
      this.process = null;
    }
  }

  private cleanup() {
    this.isConnected = false;
    for (const [id, req] of this.pendingRequests.entries()) {
      clearTimeout(req.timer);
      req.reject(new Error(`MCP server "${this.name}" disconnected.`));
    }
    this.pendingRequests.clear();
  }
}
