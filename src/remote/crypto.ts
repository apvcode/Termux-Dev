import crypto from 'crypto';

/**
 * The cryptography of the phone link, in one place. Only what Node and Android both have built in:
 * P-256 (ECDH to agree on a key, ECDSA to prove who is who), HKDF-SHA256 and AES-256-GCM.
 * The wire format is written down in docs/remote-protocol.md; the Android app implements the same.
 */

export const PROTOCOL_VERSION = 1;

// ───────────────────────── keys ─────────────────────────

/** A signing identity (ECDSA P-256). `pub` is the raw uncompressed point: 65 bytes. */
export interface Identity {
  /** The private scalar `d`, 32 bytes. */
  priv: Buffer;
  pub: Buffer;
}

export function newIdentity(): Identity {
  const e = crypto.createECDH('prime256v1');
  e.generateKeys();
  return { priv: e.getPrivateKey(), pub: e.getPublicKey() };
}

const b64u = (b: Buffer) => b.toString('base64url');

function privateKeyObject(id: Identity): crypto.KeyObject {
  return crypto.createPrivateKey({
    format: 'jwk',
    key: { kty: 'EC', crv: 'P-256', d: b64u(id.priv), x: b64u(id.pub.subarray(1, 33)), y: b64u(id.pub.subarray(33, 65)) },
  });
}

function publicKeyObject(pub: Buffer): crypto.KeyObject {
  if (pub.length !== 65 || pub[0] !== 4) throw new Error('bad public key');
  return crypto.createPublicKey({
    format: 'jwk',
    key: { kty: 'EC', crv: 'P-256', x: b64u(pub.subarray(1, 33)), y: b64u(pub.subarray(33, 65)) },
  });
}

/** ECDSA with SHA-256, signature in DER (what Android's `SHA256withECDSA` makes and checks). */
export function sign(id: Identity, data: Buffer): Buffer {
  return crypto.sign('sha256', data, privateKeyObject(id));
}

export function verify(pub: Buffer, data: Buffer, sig: Buffer): boolean {
  try {
    return crypto.verify('sha256', data, publicKeyObject(pub), sig);
  } catch {
    return false;
  }
}

/** A short name of a public key: the topic of the relay and what the screens call "fingerprint". */
export function fingerprint(pub: Buffer): string {
  return crypto.createHash('sha256').update(pub).digest('hex');
}

export const hostIdOf = (pub: Buffer): string => fingerprint(pub).slice(0, 24);

// ───────────────────────── one connection ─────────────────────────

export interface Ephemeral {
  pub: Buffer;
  agree(peerPub: Buffer): Buffer;
}

export function newEphemeral(): Ephemeral {
  const e = crypto.createECDH('prime256v1');
  e.generateKeys();
  return { pub: e.getPublicKey(), agree: (peer) => e.computeSecret(peer) };
}

export const sha256 = (...parts: Buffer[]): Buffer => {
  const h = crypto.createHash('sha256');
  for (const p of parts) h.update(p);
  return h.digest();
};

export interface SessionKeys {
  /** Client to host. */
  c2s: Buffer;
  /** Host to client. */
  s2c: Buffer;
  /** Six digits both screens show while a new phone is being paired. */
  sas: string;
}

export function deriveKeys(shared: Buffer, salt: Buffer): SessionKeys {
  const okm = Buffer.from(crypto.hkdfSync('sha256', shared, salt, Buffer.from('devx1 keys'), 68));
  const n = okm.readUInt32BE(64) % 1_000_000;
  return { c2s: okm.subarray(0, 32), s2c: okm.subarray(32, 64), sas: String(n).padStart(6, '0') };
}

export const HOST_SIG_LABEL = Buffer.from('devx1-host');
export const CLIENT_SIG_LABEL = Buffer.from('devx1-client');

// ───────────────────────── frames ─────────────────────────

/**
 * One direction of the encrypted channel. A frame is `seq (8 bytes) || ciphertext || tag (16)`; the nonce is
 * the sequence number, so a frame cannot be replayed, reordered backwards or cut out unnoticed.
 */
export class Sealer {
  private seq = 0n;
  constructor(private key: Buffer) {}

  seal(plain: Buffer): Buffer {
    const seq = Buffer.alloc(8);
    seq.writeBigUInt64BE(this.seq++);
    const nonce = Buffer.concat([Buffer.alloc(4), seq]);
    const c = crypto.createCipheriv('aes-256-gcm', this.key, nonce);
    c.setAAD(seq);
    const body = Buffer.concat([c.update(plain), c.final()]);
    return Buffer.concat([seq, body, c.getAuthTag()]);
  }
}

export class Opener {
  private last = -1n;
  constructor(private key: Buffer) {}

  /** The plain bytes, or null when the frame is forged, damaged, or a repeat of an older one. */
  open(frame: Buffer): Buffer | null {
    if (frame.length < 8 + 16) return null;
    const seq = frame.subarray(0, 8);
    const n = seq.readBigUInt64BE();
    if (n <= this.last) return null;
    try {
      const d = crypto.createDecipheriv('aes-256-gcm', this.key, Buffer.concat([Buffer.alloc(4), seq]));
      d.setAAD(seq);
      d.setAuthTag(frame.subarray(frame.length - 16));
      const plain = Buffer.concat([d.update(frame.subarray(8, frame.length - 16)), d.final()]);
      this.last = n;
      return plain;
    } catch {
      return null;
    }
  }
}

/** Compares two secrets without leaking where they differ. */
export function sameSecret(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
