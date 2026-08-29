import http from 'http';
import fs from 'fs/promises';
import fsSync from 'fs';
import path from 'path';
import os from 'os';
import { spawn } from 'child_process';
import pc from 'picocolors';
import qrcode from 'qrcode-terminal';
import { getTheme } from './theme.js';

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.ts': 'text/plain; charset=utf-8',
  '.tsx': 'text/plain; charset=utf-8',
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
  '.pdf': 'application/pdf',
  '.md': 'text/markdown; charset=utf-8',
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
    try {
      activeServer.close();
    } catch {}
    activeServer = null;
    return true;
  }
  return false;
}

function renderDirectoryHtml(dirPath: string, relPath: string, files: fsSync.Dirent[], port: number): string {
  const currentRel = relPath === '/' ? '' : relPath;
  
  const items = files
    .filter(f => !f.name.startsWith('.') && f.name !== 'node_modules')
    .sort((a, b) => {
      if (a.isDirectory() && !b.isDirectory()) return -1;
      if (!a.isDirectory() && b.isDirectory()) return 1;
      return a.name.localeCompare(b.name);
    })
    .map(f => {
      const isDir = f.isDirectory();
      const href = `${currentRel}/${encodeURIComponent(f.name)}${isDir ? '/' : ''}`;
      const ext = path.extname(f.name).toLowerCase();
      let icon = isDir ? '📁' : '📄';
      let badge = '';

      if (ext === '.html' || ext === '.htm') {
        icon = '🌐';
        badge = '<span class="badge">HTML</span>';
      } else if (['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp'].includes(ext)) {
        icon = '🖼️';
      } else if (['.js', '.ts', '.tsx', '.jsx', '.json'].includes(ext)) {
        icon = '⚡';
      } else if (ext === '.css') {
        icon = '🎨';
      }

      return `
        <li>
          <a class="file-item" href="${href}">
            <span class="icon">${icon}</span>
            <span class="name">${f.name}</span>
            ${badge}
            <span class="type">${isDir ? 'Directory' : ext || 'File'}</span>
          </a>
        </li>
      `;
    })
    .join('');

  const parentLink = currentRel && currentRel !== '/'
    ? `<li><a class="file-item" href="${path.dirname(currentRel) === '/' ? '/' : path.dirname(currentRel) + '/'}"><span class="icon">⬆️</span><span class="name">.. (Parent Directory)</span></a></li>`
    : '';

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>devx Live Preview &bull; ${relPath}</title>
  <style>
    * { box-sizing: border-box; }
    body { background: #0a0a0c; color: #e1e4e8; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace; margin: 0; padding: 24px 16px; }
    .card { background: #111116; border: 1px solid #22222c; border-radius: 12px; max-width: 800px; margin: 0 auto; padding: 24px; box-shadow: 0 8px 30px rgba(0,0,0,0.6); }
    h1 { color: #00f2fe; margin: 0 0 8px 0; font-size: 22px; display: flex; align-items: center; gap: 8px; }
    .info { color: #8b949e; font-size: 14px; margin-bottom: 20px; word-break: break-all; }
    .info code { background: #1a1a24; padding: 2px 6px; border-radius: 4px; color: #f0f6fc; }
    ul { list-style: none; padding: 0; margin: 0; }
    .file-item { display: flex; align-items: center; padding: 10px 14px; border-bottom: 1px solid #1a1a22; text-decoration: none; color: #f0f6fc; border-radius: 6px; transition: all 0.15s; }
    .file-item:hover { background: #1a1a26; transform: translateX(3px); }
    .icon { margin-right: 12px; font-size: 18px; }
    .name { flex: 1; font-weight: 500; font-size: 14px; word-break: break-all; }
    .type { color: #6e7681; font-size: 12px; margin-left: 12px; }
    .badge { background: rgba(0, 242, 254, 0.15); color: #00f2fe; border: 1px solid rgba(0, 242, 254, 0.3); padding: 2px 8px; border-radius: 12px; font-size: 11px; margin-left: 8px; font-weight: bold; }
    .footer { margin-top: 24px; text-align: center; color: #484f58; font-size: 12px; }
  </style>
</head>
<body>
  <div class="card">
    <h1>⚡ devx Live Preview</h1>
    <div class="info">
      Path: <code>${relPath}</code> &bull; Port: <code>${port}</code>
    </div>
    <ul>
      ${parentLink}
      ${items || '<li style="padding: 20px; text-align: center; color: #6e7681;">No visible files in this directory</li>'}
    </ul>
    <div class="footer">devx v1.4.3 &bull; Terminal-Native AI Assistant</div>
  </div>
</body>
</html>`;
}

export async function startServer(preferredPort = 3000): Promise<{ port: number; localUrl: string; networkUrl: string }> {
  if (activeServer) {
    stopServer();
  }

  activePort = preferredPort;

  return new Promise((resolve, reject) => {
    const server = http.createServer(async (req, res) => {
      try {
        let rawPath = req.url?.split('?')[0] || '/';
        let reqPath = decodeURIComponent(rawPath);

        const cwd = process.cwd();
        let targetPath = path.resolve(cwd, '.' + reqPath);

        // Security check: ensure path is within cwd
        const rel = path.relative(cwd, targetPath);
        if (rel.startsWith('..') || path.isAbsolute(rel)) {
          res.writeHead(403, { 'Content-Type': 'text/plain' });
          res.end('Forbidden');
          return;
        }

        // Security check: block dotfiles, hidden directories (.env, .git, etc.) and node_modules
        const segments = reqPath.split(/[\/\\]/).filter(Boolean);
        if (segments.some(seg => (seg.startsWith('.') && seg !== '.') || seg === 'node_modules')) {
          res.writeHead(403, { 'Content-Type': 'text/plain' });
          res.end('Forbidden');
          return;
        }

        // 1. Root / Directory Request Handling
        if (fsSync.existsSync(targetPath)) {
          const stat = await fs.stat(targetPath);
          if (stat.isDirectory()) {
            // Check for index.html in directory
            const possibleIndexes = [
              path.join(targetPath, 'index.html'),
              path.join(targetPath, 'index.htm'),
              path.join(targetPath, 'public/index.html'),
              path.join(targetPath, 'dist/index.html'),
              path.join(targetPath, 'build/index.html')
            ];

            for (const idxPath of possibleIndexes) {
              if (fsSync.existsSync(idxPath)) {
                const content = await fs.readFile(idxPath);
                res.writeHead(200, {
                  'Content-Type': 'text/html; charset=utf-8',
                  'Access-Control-Allow-Origin': '*'
                });
                res.end(content);
                return;
              }
            }

            // If no index.html, render sleek directory explorer
            const dirents = await fs.readdir(targetPath, { withFileTypes: true });
            const dirHtml = renderDirectoryHtml(targetPath, reqPath, dirents, activePort);
            res.writeHead(200, {
              'Content-Type': 'text/html; charset=utf-8',
              'Access-Control-Allow-Origin': '*'
            });
            res.end(dirHtml);
            return;
          }
        }

        // 2. Fallback check for missing .html extension
        if (!fsSync.existsSync(targetPath)) {
          const withHtml = `${targetPath}.html`;
          if (fsSync.existsSync(withHtml)) {
            targetPath = withHtml;
          }
        }

        // 3. Serve File
        if (fsSync.existsSync(targetPath)) {
          const ext = path.extname(targetPath).toLowerCase();
          const contentType = MIME_TYPES[ext] || 'application/octet-stream';
          const content = await fs.readFile(targetPath);

          res.writeHead(200, {
            'Content-Type': contentType,
            'Access-Control-Allow-Origin': '*'
          });
          res.end(content);
          return;
        }

        // 4. SPA Fallback: check if root index.html exists
        const rootIndex = path.join(cwd, 'index.html');
        if (fsSync.existsSync(rootIndex)) {
          const content = await fs.readFile(rootIndex);
          res.writeHead(200, {
            'Content-Type': 'text/html; charset=utf-8',
            'Access-Control-Allow-Origin': '*'
          });
          res.end(content);
          return;
        }

        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`404 Not Found: ${reqPath}`);
      } catch (err: any) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`Internal Server Error: ${err.message}`);
      }
    });

    let retryCount = 0;
    server.on('error', (err: any) => {
      if (err.code === 'EADDRINUSE') {
        retryCount++;
        if (retryCount > 20) {
          reject(new Error(`Could not find an open port after 20 attempts (started at ${preferredPort})`));
          return;
        }
        activePort++;
        server.listen(activePort, '0.0.0.0');
      } else {
        reject(err);
      }
    });

    server.listen(activePort, '0.0.0.0', () => {
      activeServer = server;
      const localIp = getLocalIp();
      const localUrl = `http://localhost:${activePort}`;
      const networkUrl = `http://${localIp}:${activePort}`;

      // If on Android Termux, attempt to open browser
      if (process.env.PREFIX?.includes('com.termux')) {
        try {
          const opener = spawn('termux-open-url', [localUrl], { stdio: 'ignore', detached: true });
          opener.on('error', () => {});
          opener.unref();
        } catch {}
      }

      resolve({ port: activePort, localUrl, networkUrl });
    });
  });
}

export function getQrCodeString(text: string): Promise<string> {
  return new Promise((resolve) => {
    try {
      qrcode.generate(text, { small: true }, (qr) => {
        resolve(qr);
      });
    } catch {
      resolve('');
    }
  });
}

export async function displayServerBanner(localUrl: string, networkUrl: string): Promise<void> {
  const th = getTheme();
  const qr = await getQrCodeString(networkUrl);
  const cols = Math.min(process.stdout.columns || 80, 80);
  const cardWidth = Math.max(36, Math.min(cols - 4, 66));
  const innerWidth = cardWidth - 2;
  
  const title = ' 🌐 Live Web Preview ';
  const topFill = Math.max(2, cardWidth - 3 - title.length);
  console.log('\n' + th.colorFn('┌─') + pc.bold(title) + th.colorFn('─'.repeat(topFill) + '┐'));

  const printRow = (content: string) => {
    const visibleLength = content.replace(/\u001b\[[0-9;]*m/g, '').length;
    const padding = Math.max(0, innerWidth - visibleLength);
    console.log(th.colorFn('│') + content + ' '.repeat(padding) + th.colorFn('│'));
  };

  printRow(`  ${pc.bold('Local:')}   ${pc.cyan(localUrl)}`);
  printRow(`  ${pc.bold('Network:')} ${pc.green(networkUrl)}`);
  printRow(' ');
  printRow(`  ${pc.bold('📱 Mobile QR:')}`);

  if (qr) {
    const qrLines = qr.trim().split('\n');
    for (const ql of qrLines) {
      printRow(`  ${ql}`);
    }
  }

  console.log(th.colorFn('└' + '─'.repeat(innerWidth) + '┘\n'));
}
