import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import fs from 'fs/promises';
import http from 'http';
import os from 'os';
import path from 'path';
import { isAllowedHost, isBlockedName, startServer, stopServer } from '../../src/cli/server.js';

const WIN = process.platform === 'win32';

function get(port: number, urlPath: string, headers: Record<string, string> = {}): Promise<{ status: number; headers: http.IncomingHttpHeaders; body: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: urlPath, method: 'GET', headers: { Host: `127.0.0.1:${port}`, ...headers } },
      (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => resolve({ status: res.statusCode!, headers: res.headers, body }));
      }
    );
    req.on('error', reject);
    req.end();
  });
}

describe('isAllowedHost', () => {
  it('accepts IPs, localhost, single-label and .local names', () => {
    for (const host of ['127.0.0.1:3000', 'localhost:3000', '192.168.1.20:3000', '[::1]:3000', 'mylaptop:3000', 'mylaptop.local:3000']) {
      expect(isAllowedHost(host), host).toBe(true);
    }
  });
  it('rejects public domain names (DNS rebinding) and a missing header', () => {
    for (const host of ['evil.example.com:3000', 'rebind.attacker.net', '127.0.0.1.evil.com:3000', undefined]) {
      expect(isAllowedHost(host as any), String(host)).toBe(false);
    }
  });
});

describe('isBlockedName', () => {
  it('blocks dotfiles, keys and secret stores but not regular files', () => {
    for (const name of ['.env', '.git', 'server.pem', 'tls.key', 'id_rsa', 'secrets.yaml', 'credentials.json', 'node_modules']) {
      expect(isBlockedName(name), name).toBe(true);
    }
    for (const name of ['index.html', 'app.js', 'keyboard.png', 'README.md', 'monkey.txt']) {
      expect(isBlockedName(name), name).toBe(false);
    }
  });
});

describe('preview server', () => {
  const original = process.cwd();
  let dir: string;
  let outside: string;
  let port: number;

  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-serve-'));
    outside = await fs.mkdtemp(path.join(os.tmpdir(), 'devx-serve-out-'));
    await fs.writeFile(path.join(dir, 'index.html'), '<h1>hello</h1>');
    await fs.writeFile(path.join(dir, 'app.js'), 'console.log(1)');
    await fs.writeFile(path.join(dir, '.env'), 'SECRET=1');
    await fs.writeFile(path.join(dir, 'server.pem'), 'PRIVATE KEY');
    await fs.writeFile(path.join(dir, 'secrets.yaml'), 'a: b');
    await fs.writeFile(path.join(outside, 'passwd'), 'root:x:0:0');
    await fs.symlink(outside, path.join(dir, 'escape'));
    await fs.symlink(path.join(dir, '.env'), path.join(dir, 'innocent.txt'));
    process.chdir(dir);
    ({ port } = await startServer(34000 + Math.floor(Math.random() * 1000)));
  });
  afterEach(async () => {
    stopServer();
    process.chdir(original);
    await fs.rm(dir, { recursive: true, force: true });
    await fs.rm(outside, { recursive: true, force: true });
  });

  it('serves normal files without opening them to every website (no CORS wildcard)', async () => {
    const res = await get(port, '/app.js');
    expect(res.status).toBe(200);
    expect(res.body).toBe('console.log(1)');
    expect(res.headers['access-control-allow-origin']).toBeUndefined();
    expect((await get(port, '/')).body).toContain('hello');
  });

  it('refuses a request whose Host header is a public domain (DNS rebinding)', async () => {
    const res = await get(port, '/app.js', { Host: `evil.example.com:${port}` });
    expect(res.status).toBe(403);
    expect(res.body).not.toContain('console.log');
  });

  it('does not serve dotfiles, keys or secret stores', async () => {
    for (const p of ['/.env', '/server.pem', '/secrets.yaml', '/%2eenv']) {
      expect((await get(port, p)).status, p).toBe(403);
    }
  });

  it('does not follow symlinks out of the project or to blocked files', async () => {
    expect((await get(port, '/escape/passwd')).status).toBe(403);
    expect((await get(port, '/innocent.txt')).status).toBe(403);
  });

  it.skipIf(WIN)('escapes file names in the directory listing', async () => {
    await fs.mkdir(path.join(dir, 'sub'));
    await fs.writeFile(path.join(dir, 'sub', '<img src=x onerror=alert(1)>.txt'), 'x');
    const res = await get(port, '/sub/');
    expect(res.status).toBe(200);
    expect(res.body).not.toContain('<img src=x');
    expect(res.body).toContain('&lt;img src=x onerror=alert(1)&gt;.txt');
  });

  it('hides blocked files from the directory listing', async () => {
    await fs.rm(path.join(dir, 'index.html'));
    const res = await get(port, '/');
    expect(res.body).toContain('app.js');
    expect(res.body).not.toContain('server.pem');
    expect(res.body).not.toContain('secrets.yaml');
  });
});
