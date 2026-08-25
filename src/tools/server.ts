import { Tool } from '../core/types.js';
import { startServer, stopServer, isServerRunning, getServerPort } from '../cli/server.js';

export const servePreviewTool: Tool = {
  name: 'serve_preview',
  definition: {
    name: 'serve_preview',
    description: 'Start or stop the built-in local live web preview server to view HTML/web apps in the browser.',
    parameters: {
      type: 'object',
      properties: {
        action: {
          type: 'string',
          enum: ['start', 'stop', 'status'],
          description: 'Action to perform: start the server, stop it, or check status'
        },
        port: {
          type: 'number',
          description: 'Optional preferred port number (defaults to 3000)'
        }
      },
      required: ['action']
    }
  },
  validateArgs: (args: any) => {
    if (!args || !args.action) {
      throw new Error('action is required');
    }
  },
  execute: async (args: { action: 'start' | 'stop' | 'status'; port?: number }) => {
    if (args.action === 'stop') {
      const stopped = stopServer();
      return stopped ? 'Web preview server stopped.' : 'No web preview server was running.';
    }

    if (args.action === 'status') {
      const running = isServerRunning();
      return running ? `Web server is running on port ${getServerPort()}.` : 'Web server is not running.';
    }

    const preferredPort = args.port || 3000;
    try {
      const { port, localUrl, networkUrl } = await startServer(preferredPort);
      return `Web Server is now live!\n• Local URL: ${localUrl}\n• Network URL: ${networkUrl}\nOpened in browser automatically.`;
    } catch (err: any) {
      throw new Error(`Failed to start web server: ${err.message}`);
    }
  }
};
