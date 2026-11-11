# devx remote: how the phone talks to this computer

The host (`devx remote start`) keeps sessions and runs the agent. The phone app is only a screen and a keyboard for it.
This is the wire format; `src/remote/` (TypeScript) and the Android app implement the same.

## Layers

```
app messages   JSON objects:  req / res / ev / g / rpc
encrypted      AES-256-GCM frames, keys from the handshake
handshake      P-256 ECDH + ECDSA, both sides prove who they are
link           whole binary messages: direct WebSocket, or the relay
```

A link is any pipe that carries messages. Nothing above it trusts it, so the relay (a public MQTT broker) sees only
who talks to which topic and how much.

## Pairing

`devx remote pair` prints a QR code with this text:

```
devx1:<base64url(JSON)>   JSON = { v:1, hp:<host public key>, t:<one-time token>, n:<computer name>, a:["ip:port",...], r:[broker ids] }
```

`hp` is the host's P-256 public key (65 bytes, uncompressed, base64url) and is **pinned** by the phone: every later
connection is checked against it. `t` is 16 random bytes, valid for 5 minutes, once. The phone makes its own P-256
signing key (in Android: the Keystore, never leaves the phone) and a random 16-byte device id (32 hex characters).

Connecting with a token also needs the person at the computer: both screens show the same six digits (derived from
the handshake) and the computer waits for `y`. A copied QR code alone gets nobody in.

## Handshake (every connection)

Packets: first byte is the type. `0x01` hello, `0x02` hello2, `0x03` data, `0x04` deny (JSON `{k:"deny",why}`).

1. phone to host, `0x01` + JSON `helloBody` = `{"k":"hello","v":1,"did":<32 hex>,"e":<ephemeral public key b64>,"n":<16 random bytes b64>}`
   (these exact bytes are signed and hashed: send and keep them as they are)
2. host to phone, `0x02` + JSON `{"k":"hello2","e":<eph key b64>,"n":<16 bytes b64>,"sig":<b64>}`, where
   `sig` = ECDSA-SHA256 (DER) over `"devx1-host" || helloBody || eS(65) || nS(16)` with the host's key.
   The phone checks it with the pinned `hp`; a wrong signature ends the connection.
3. Both sides: `salt = SHA256(helloBody || eS || nS)`, `shared = ECDH(eC, eS)` (32 bytes),
   `okm = HKDF-SHA256(ikm=shared, salt, info="devx1 keys", length=68)`:
   `c2s = okm[0..32]`, `s2c = okm[32..64]`, `sas = (uint32 big-endian of okm[64..68]) mod 1000000`, six digits, zero padded.
4. phone to host, `0x03` + encrypted frame (c2s) of `{"k":"auth","sig":<b64>}` where `sig` = ECDSA-SHA256 (DER) over
   `"devx1-client" || salt` with the phone's key. When pairing, also `"pair":{"tok":<b64>,"spk":<phone public key b64>,"name":<text>}`.
5. host to phone, encrypted (s2c): `{"k":"ready","paired":bool,"host":{name,os,version,home,hostId,protocol}}`, or
   `{"k":"denied","why":"token"|"refused"}`, or a plain `0x04` deny for an unknown device.

Frames: `seq (8 bytes, big-endian) || ciphertext || tag (16)`, AES-256-GCM, nonce = `00 00 00 00 || seq`, AAD = `seq`.
Each direction counts from 0; a frame whose `seq` is not above the last accepted one is dropped (no replays).
The plaintext of a frame is one UTF-8 JSON object.

`{"k":"ping","t":ms}` / `{"k":"pong","t":ms}` every 15 s in both directions; 45 s of silence ends the connection.

## Links

**Direct**: WebSocket, binary messages, one packet per message. The host listens on port 47821 (all interfaces); the QR
lists its LAN addresses. Also works through a VPN (Tailscale) or a forwarded port: the user adds the address by hand.

**Relay**: MQTT 3.1.1 over WebSocket (`wss://broker.hivemq.com:8884/mqtt`, `wss://test.mosquitto.org:8081`, ...; ids in
`relay.ts`). `hostId` = first 24 hex characters of SHA-256 of the host public key.
- the phone picks a random 8-byte `conn`, subscribes to `devx/v1/<hostId>/dn/<conn hex>`, publishes to `devx/v1/<hostId>/up`
- every MQTT payload is a chunk: `0x01 || conn(8) || msgId u32 || index u16 || total u16 || data` (data at most 12 KB); messages are
  cut and joined by this header. `0x02 || conn(8)` means "goodbye".
- QoS 0. A lost frame shows up as a gap in the session event numbers; the app then asks for a fresh snapshot.

## App messages (inside the encrypted channel)

Phone to host: `{"k":"req","id":n,"m":"method","p":{...}}`, answered by `{"k":"res","id":n,"ok":true,"r":...}` or `{"ok":false,"err":"text"}`.
Host to phone: session events `{"k":"ev","s":<session id>,"n":<seq>,"e":{"e":"kind",...}}`; global news `{"k":"g","e":{"e":"sessions"}}`;
questions to the phone `{"k":"rpc","id":..,"m":"memory","p":{action,query}}`, answered with `{"k":"rpc.res","id":..,"ok":true,"r":...}`.

Methods: `hello {name, memory}`; `sessions.list {q?}`; `session.open {id? | cwd?, plan?}` (returns the snapshot and attaches);
`session.detach`; `session.delete {id}`; `session.reset`; `session.send {s,text,files:[uid]}`; `session.stop`; `session.mode {plan,yolo,extra}`;
`session.model {provider,model}`; `session.effort {level}`; `session.rename`; `session.compact`; `session.undo`; `session.info`; `session.tasks`;
`session.stopTask`; `session.trust`; `session.snapshot`; `session.expand {line}`; `commands.list`; `perm.answer {s,id,answer}`;
`ask.answer {s,id,answers}`; `plan.decide {s,id,choice}`; `dirs.list {path?}`; `providers.list`; `models.list {provider?}`;
`provider.add`; `provider.key`; `provider.remove`; `upload.begin/chunk/end` (base64 chunks of up to 32 KB; 25 MB at most).

Session events (`e.e`): `turn_start turn_end user text reasoning tool_gen tool_start tool_end usage state title queued queued_taken
perm perm_done ask ask_done plan plan_done compact undone reset note system error reconnecting reconnected gone`.
`text`/`reasoning` carry deltas `d`. `n` rises by one per event; a gap means a lost packet: ask for `session.snapshot` again.

## What the phone shares

`hello {name, memory:true}`: the name goes into the system prompt of the chat, and the agent gets a `phone_memory` tool that asks the
phone (`rpc`) for its notes only while the phone is attached and agrees. Nothing is copied to the computer.
