import { startServer, stopServer, isServerRunning, getServerPort, displayServerBanner } from '../cli/server.js';
export const servePreviewTool = {
    name: 'serve_preview',
    definition: {
        name: 'serve_preview',
        description: 'Start or stop the built-in local live web preview server to view HTML/web apps in the browser with instant QR-code.',
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
    validateArgs: (args) => {
        if (!args || !args.action) {
            throw new Error('action is required');
        }
    },
    execute: async (args) => {
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
            await displayServerBanner(localUrl, networkUrl);
            return `Web Server is now live!\n• Local URL: ${localUrl}\n• Network URL: ${networkUrl}\nOpened in browser automatically with mobile QR-code displayed in terminal.`;
        }
        catch (err) {
            throw new Error(`Failed to start web server: ${err.message}`);
        }
    }
};
