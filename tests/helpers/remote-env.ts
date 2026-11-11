import fs from 'fs';
import os from 'os';
import path from 'path';
import http from 'http';
import mqttPacket from 'mqtt-packet';
import { WebSocket, WebSocketServer } from 'ws';
import { RemoteStore } from '../../src/remote/store.js';

export function tempStore(): RemoteStore {
  return new RemoteStore(fs.mkdtempSync(path.join(os.tmpdir(), 'devx-remote-')));
}

/**
 * A tiny MQTT 3.1.1 broker over WebSocket for the tests: connect, subscribe (exact topics), publish at QoS 0,
 * ping. It is also the reference for what the Android client must speak.
 */
export async function localBroker(): Promise<{ url: string; close(): Promise<void> }> {
  const server = http.createServer();
  const wss = new WebSocketServer({ server, handleProtocols: (p) => (p.has('mqtt') ? 'mqtt' : false) });
  const subs = new Map<string, Set<WebSocket>>();

  wss.on('connection', (ws) => {
    // MQTT packets may be split over several WebSocket messages (mqtt.js does that): it is a byte stream.
    const parser = mqttPacket.parser({ protocolVersion: 4 });
    const send = (p: any) => { if (ws.readyState === WebSocket.OPEN) ws.send(mqttPacket.generate(p)); };
    parser.on('packet', (p: any) => {
      if (p.cmd === 'connect') send({ cmd: 'connack', returnCode: 0, sessionPresent: false });
      else if (p.cmd === 'subscribe') {
        for (const s of p.subscriptions) {
          if (!subs.has(s.topic)) subs.set(s.topic, new Set());
          subs.get(s.topic)!.add(ws);
        }
        send({ cmd: 'suback', messageId: p.messageId, granted: p.subscriptions.map(() => 0) });
      } else if (p.cmd === 'publish') {
        for (const other of subs.get(p.topic) ?? []) {
          if (other.readyState === WebSocket.OPEN) other.send(mqttPacket.generate({ cmd: 'publish', topic: p.topic, payload: p.payload, qos: 0, retain: false, dup: false }));
        }
        if (p.qos === 1) send({ cmd: 'puback', messageId: p.messageId });
      } else if (p.cmd === 'pingreq') send({ cmd: 'pingresp' });
      else if (p.cmd === 'disconnect') ws.close();
    });
    parser.on('error', () => ws.terminate());
    ws.on('message', (data) => parser.parse(Buffer.isBuffer(data) ? data : Buffer.concat(data as Buffer[])));
    ws.on('close', () => { for (const set of subs.values()) set.delete(ws); });
  });

  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const port = (server.address() as any).port;
  return {
    url: `ws://127.0.0.1:${port}`,
    close: () => new Promise((r) => { wss.clients.forEach((c) => c.terminate()); server.close(() => r()); server.closeAllConnections?.(); }),
  };
}
