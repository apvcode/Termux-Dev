import http from 'http';
import { WebSocket, WebSocketServer } from 'ws';
import { Link } from './secure.js';

/** A link over a WebSocket (binary messages): the direct connection, in either role. */
export function wsLink(ws: WebSocket): Link {
  const link: Link = {
    kind: 'direct',
    send: (data) => { if (ws.readyState === WebSocket.OPEN) ws.send(data); },
    close: () => { try { ws.close(); } catch {} },
    onMessage: () => {},
    onClose: () => {},
  };
  ws.on('message', (data, isBinary) => {
    if (!isBinary) return;
    link.onMessage(Buffer.isBuffer(data) ? data : Buffer.concat(data as Buffer[]));
  });
  ws.on('close', () => link.onClose());
  ws.on('error', () => link.onClose());
  return link;
}

export interface DirectServer {
  port: number;
  close(): Promise<void>;
}

/**
 * The listener for phones that can reach this computer directly: on the same Wi-Fi, or through a VPN
 * such as Tailscale. It answers only to a WebSocket upgrade and says nothing about itself otherwise;
 * every connection must still pass the handshake.
 */
export function listenDirect(port: number, onLink: (link: Link, remote: string) => void, host = '0.0.0.0'): Promise<DirectServer> {
  return new Promise((resolve, reject) => {
    const server = http.createServer((_req, res) => { res.statusCode = 404; res.end(); });
    const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024 });
    // A new connection costs the host a key agreement: a flood from one address is cut off.
    const recent = new Map<string, number[]>();
    server.on('upgrade', (req, socket, head) => {
      const ip = req.socket.remoteAddress ?? '?';
      const now = Date.now();
      const hits = (recent.get(ip) ?? []).filter((t) => now - t < 10_000);
      hits.push(now);
      recent.set(ip, hits);
      if (hits.length > 30) { socket.destroy(); return; }
      wss.handleUpgrade(req, socket, head, (ws) => onLink(wsLink(ws), ip));
    });
    const sweep = setInterval(() => { const now = Date.now(); for (const [ip, h] of recent) if (h.every((t) => now - t > 10_000)) recent.delete(ip); }, 30_000);
    sweep.unref();
    server.once('error', reject);
    server.listen(port, host, () => {
      const addr = server.address();
      resolve({
        port: typeof addr === 'object' && addr ? addr.port : port,
        close: () => new Promise((r) => { clearInterval(sweep); wss.clients.forEach((c) => c.terminate()); server.close(() => r()); server.closeAllConnections?.(); }),
      });
    });
  });
}

/** Opens a direct connection to `url` (the phone's way; also used by tests). */
export function dialDirect(url: string): Promise<Link> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { handshakeTimeout: 5000 });
    ws.once('open', () => resolve(wsLink(ws)));
    ws.once('error', reject);
  });
}
