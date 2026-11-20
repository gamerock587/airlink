# AirLink discovery/pairing v2 (app 1.3 recovery extension)

Windows is the server; Android is the initiating client. This is incompatible with v1 discovery/pairing. The 988-byte encrypted audio format remains unchanged.

## Discovery

Windows binds UDP 45832. Android sends `AIRLINK_DISCOVER_V2 <nonce>` where nonce is 32 hex characters to subnet/global broadcast addresses, repeating during a three-second scan. With a manual PC IPv4 address, it sends directly instead. Windows replies to the source address/port:

```json
{"type":"airlink-server","version":2,"port":45832,"nonce":"echoed query nonce","id":"persistent Windows server ID","name":"Windows computer name"}
```

Discovery is unauthenticated. Android checks version and nonce, displays the server name/address and uses the advertised ID for key derivation. It does not establish a session from discovery alone. App 1.2 advertises and pairs on the same port, 45832. App 1.1 servers omit `port`; 1.2 Android falls back to 45833 for those. Windows 1.2 also accepts 1.1 clients on 45833.

## Android-initiated pairing

Windows generates a new six-digit code when starting its server. On its network worker, Android opens one ephemeral UDP socket, connects it to the selected Windows IP and advertised port, generates a random 16-byte session as lowercase hex, then derives:

```
PBKDF2-HMAC-SHA256(
  password = six-digit ASCII Windows code,
  salt = UTF8("AirLink/1/" + serverId + "/" + sessionHex),
  iterations = 100000, length = 32 bytes)
```

Android sends `{"type":"pair","session":"...","name":"phone model","proof":"..."}`. Proof is lowercase hex HMAC-SHA256(key, UTF8("pair:" + sessionHex)). Windows checks it and replies `{"type":"ready","session":"...","proof":"..."}` with the `ready:` prefix. Android verifies both session and proof before accepting. Retries with the same session/peer/proof get another ready response without restarting audio or sequence numbers.

Windows accepts one peer/session. It returns unauthenticated diagnostic `{"type":"error","reason":"wrong-code"}` or `busy` as appropriate. Errors never establish sessions. Pairing attempts are rate-limited globally and per source IPv4 address. A persistent wrong code does not create a connection.

The Windows audio pump sends to the phone's initiating UDP socket from the same Windows socket used for pairing. There is no Android listening server or separate advertised phone port. Requests from both supported ports are serialized through a shared pairing gate, so simultaneous requests cannot create two active clients.

## Control and liveness

Both sides send controls with `type`, `session`, `proof`; the proof prefix matches the type (`ping:`, `pong:`, `stop:`). Both sides send independent one-second heartbeats, even while capture/playback is interrupted. Windows keeps a session for up to 120 seconds without a valid reply and reports a recovering state after three seconds. Android attempts recovery after a network error or eight seconds without valid packets and retries for up to 90 seconds. A valid stop still ends the session immediately.

### Authenticated socket restoration (app 1.3)

Android creates a new UDP socket and sends `{"type":"resume","session":"...","counter":N,"proof":"..."}`. N is a positive, strictly increasing counter. Proof is HMAC-SHA256(key, UTF8("resume:" + sessionHex + ":" + decimalCounter)). Windows verifies the proof and counter before changing the phone endpoint. It replies with type `resumed`, the same session/counter, and a proof with prefix `resumed:`. Exact retries from the same endpoint/counter are idempotent; an older counter or the same counter from a different endpoint cannot redirect audio. The AES key and Windows packet sequence are retained, so AES-GCM nonces are never reused.

The phone persists the counter before sending a restoration request in a short-lived, Android-Keystore-wrapped checkpoint. That allows a recreated sticky service to restore the session without saving the pairing PIN. Explicit disconnect erases the checkpoint. Windows restart or expired leases require fresh pairing.

Ordinary ping/pong/stop controls have no independent replay counter; socket-restoration controls do. Use a private trusted LAN, not an internet-facing deployment. Discovery names and errors do not authenticate identity; authenticated ready establishes knowledge of the code.

## Audio packet

| Offset | Length | Data |
|---|---|---|
| 0 | 4 | ASCII `ALN1` |
| 4 | 8 | Unsigned little-endian packet sequence, starting at zero |
| 12 | 960 | AES-GCM ciphertext of 240 frames of 48 kHz stereo signed PCM16, little-endian L/R interleaved |
| 972 | 16 | GCM authentication tag |

The 12-byte nonce is the first four bytes of the binary session ID followed by the eight on-wire sequence bytes. Additional authenticated data is the first 12 header bytes. Never reuse a sequence with the same session/key. Android checks the GCM tag and discards played/duplicate sequences. The bounded jitter queue reorders, conceals losses with silence, trims backlog and rebuffers after ten consecutive gaps. No retransmission or forward error correction is provided.
