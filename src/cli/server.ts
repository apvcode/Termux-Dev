import http from 'http';
import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { spawn } from 'child_process';
import pc from 'picocolors';

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.wav': 'audio/wav',
  '.mp3': 'audio/mpeg',
  '.ogg': 'audio/ogg',
  '.wasm': 'application/wasm',
  '.txt': 'text/plain; charset=utf-8'
};

let activeServer: http.Server | null = null;
let activePort = 3000;

function getLocalIp(): string {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name] || []) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return 'localhost';
}

export function isServerRunning(): boolean {
  return activeServer !== null;
}

export function getServerPort(): number {
  return activePort;
}

export function stopServer(): boolean {
  if (activeServer) {
    activeServer.close();
    activeServer = null;
    return true;
  }
  return false;
}

export async function startServer(preferredPort = 3000): Promise<{ port: number; localUrl: string; networkUrl: string }> {
  if (activeServer) {
    stopServer();
  }

  activePort = preferredPort;

  return new Promise((resolve, reject) => {
    const server = http.createServer(async (req, res) => {
      try {
        let reqPath = decodeURIComponent(req.url?.split('?')[0] || '/');
        if (reqPath === '/') {
          reqPath = '/index.html';
        }

        const filePath = path.join(process.cwd(), reqPath);

        // Security check: ensure path is within cwd
        if (!filePath.startsWith(process.cwd())) {
          res.writeHead(403, { 'Content-Type': 'text/plain' });
          res.end('Forbidden');
          return;
        }

        if (!fsSync.existsSync(filePath)) {
          // If html file requested without extension
          const withHtml = `${filePath}.html`;
          if (fsSync.existsSync(withHtml)) {
            const content = await fs.readFile(withHtml);
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(content);
            return;
          }

          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end(`404 Not Found: ${reqPath}`);
          return;
        }

        const stat = await fs.stat(filePath);
        if (stat.isDirectory()) {
          const indexPath = path.join(filePath, 'index.html');
          if (fsSync.existsSync(indexPath)) {
            const content = await fs.readFile(indexPath);
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(content);
            return;
          }
          res.writeHead(403, { 'Content-Type': 'text/plain' });
          res.end('Directory listing disabled');
          return;
        }

        const ext = path.extname(filePath).toLowerCase();
        const contentType = MIME_TYPES[ext] || 'application/octet-stream';
        const content = await fs.readFile(filePath);

        res.writeHead(200, {
          'Content-Type': contentType,
          'Access-Control-Allow-Origin': '*'
        });
        res.end(content);
      } catch (err: any) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end(`Internal Server Error: ${err.message}`);
      }
    });

    server.on('error', (err: any) => {
      if (err.code === 'EADDRINUSE') {
        server.listen(activePort + 1);
        activePort++;
      } else {
        reject(err);
      }
    });

    server.listen(activePort, () => {
      activeServer = server;
      const localIp = getLocalIp();
      const localUrl = `http://localhost:${activePort}`;
      const networkUrl = `http://${localIp}:${activePort}`;

      // If in Android Termux, try to open in browser
      if (process.env.PREFIX?.includes('com.termux')) {
        try {
          spawn('termux-open-url', [localUrl], { stdio: 'ignore' });
        } catch {}
      }

      resolve({ port: activePort, localUrl, networkUrl });
    });
  });
}
