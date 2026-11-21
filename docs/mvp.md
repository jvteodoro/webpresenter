# MVP

The MVP focuses on the **MediaPlayer** and on the minimum frontend and central server features needed to play media on it. It is a thin vertical slice: upload media through the web, store it in the S3 store (SeaweedFS), build a simple event, pre-cache it on the player, and control playback live from the web.

See `architecture.md` for the overall architecture and decisions.

## Scope

### In scope
- Web login through Keycloak (fixed global roles).
- Media upload through the web (video, audio, image files) stored in SeaweedFS (S3 API).
- Simple event: a flat, ordered list of items. Each item is either a media asset or a text slide.
- MediaPlayer connection by configured server URL, appearing as *pending* until an operator approves it.
- Mandatory pre-cache of the event's media on the player, with integrity check (sha256).
- Live control from the web: play, next, previous, stop, show rest screen.
- Playback of video, audio, images, and text slides on a single screen.
- Configurable rest screen and fade in/out transitions.

### Out of scope (later iterations)
- mDNS discovery (the player is configured with the server URL in the MVP).
- YouTube ingestion, media workers, lyrics timing, Demucs.
- Full event planning model (groups, titles, event roles, people responsible).
- Multiple screens per player and multiple players in sync.
- Musician view (lyrics, chords, sheet music).
- Android packaging.

## Repository layout

Gradle (Kotlin DSL) multi-project monorepo:

```
webpresenter/
├── settings.gradle.kts
├── protocol/   # shared contract: Java records for WebSocket messages
├── server/     # Spring Boot + Spring Modulith
├── player/     # JavaFX + native playback, packaged with jpackage
├── web/        # Angular
├── deploy/     # docker-compose, Keycloak realm export
└── docs/
```

`protocol` is a dependency of both `server` and `player`, so the contract cannot drift between them.

## Server ↔ player protocol

WebSocket with JSON messages in an envelope: `{ "type", "id", "ts", "payload" }`.

| Direction | Message | Purpose |
|---|---|---|
| Player → Server | `HELLO` | deviceId, player version, available displays, device token (if already paired). |
| Server → Player | `PAIRING_PENDING` | Device is waiting for operator approval. |
| Server → Player | `PAIRED { token }` | Device approved. Token is issued by the server and stored hashed. |
| Server → Player | `CONFIG` | Display to use, rest screen asset, default transition. |
| Server → Player | `PREPARE { eventId, title, items[] }` | The current event: ordered items (media refs with presigned URL, sha256, size; or slide text). |
| Player → Server | `CACHE_STATUS { eventId, itemsReady, itemsTotal, itemsFailed, bytesReady, bytesTotal }` | Progress of everything the player must cache (rest screen + current event), throttled to 2/s. |
| Server → Player | `PLAY { itemId, startAt, transition }` | Play a specific item at a scheduled instant. |
| Server → Player | `PAUSE` / `RESUME` / `SHOW_REST` | Playback control. |
| Player → Server | `PLAYBACK_STATE { itemId, status, positionMs, durationMs, detail }` | What the player shows; about once per second while media plays. |
| Player → Server | `ERROR` | Failures (missing cache, decode error…). |

Implemented: `HELLO`, `PAIRING_PENDING`, `PAIRED`, `CONFIG`, `PREPARE`, `PLAY`, `PAUSE`, `RESUME`,
`SHOW_REST`, `PLAYBACK_STATE`, `REVOKED`, `CACHE_STATUS`, `ERROR` (see `protocol/src/main/java/webpresenter/protocol/Message.java`). Peers ignore
unknown fields and default missing ones, so slightly different versions keep talking.

Design rules:
- The player has no notion of *next* or *previous*. The `live` module computes them and always sends `PLAY` for a specific item. Reordering during a live event therefore requires nothing from the player.
- Device authentication in the MVP uses a server-issued device token. Human users authenticate through Keycloak.
- If the connection is lost, the player finishes the current item, shows the rest screen, and reconnects with backoff (1 s doubling up to 5 s).
- A device can only be approved while connected: the token travels only over the live connection and the server keeps only its SHA-256.
- An approved device presenting a missing or wrong token is rejected (`INVALID_TOKEN`) and never downgraded; an operator removes it to pair again.
- A newer connection with the same device id replaces the older one, which is closed with code `4001` and waits 60 s before retrying (prevents two copies of one installation from flapping).
- Messages to players are sent after the database transaction commits, never before.
- The live page receives updates through SSE; the devices page (`GET /api/devices`, 3 s) and the event readiness panel (`GET /api/devices/readiness`, 2 s) still poll.

## Events (M4)

- An event is an ordered list of items: library media or text slides. Leaders, AV technicians and admins edit; everyone signed in can read.
- Exactly zero or one event is the **current event** (`POST /api/events/{id}/current`). Players receive it in `PREPARE` on connection and whenever it or its items change, and download all its media. Preparing again retries failed downloads.
- A file is downloaded once even if used by several items or also as the rest screen.
- Library media used in an event cannot be deleted (409 listing the events), so a plan never silently loses an item.
- Concurrent edits of the same event are serialized with optimistic locking; the loser gets 409 and the UI reloads the event.

## Central server modules (MVP subset)

| Module | MVP responsibility |
|---|---|
| `identity` | Validate Keycloak JWTs, map realm roles. |
| `storage` | Upload to the S3 store, generate presigned download URLs. |
| `library` | Media asset registry (type, sha256, size, duration via ffprobe), upload endpoint. |
| `planning` | Event as a flat ordered list of items (media or text slide), the current event, media-usage veto. |
| `devices` | Player connection, pending/approval, device token, configuration, rest screen. |
| `live` | Open an event live, play/next/previous/stop/rest, broadcast live state to the web. |

## Frontend screens (MVP)

1. **Login** through Keycloak.
2. **Library**: upload and list media.
3. **Event editor**: create an event, add media items and text slides, reorder.
4. **Devices**: approve pending players, set the rest screen.
5. **Live**: current and next item, control buttons, cache status per player.

## Milestones

| # | Deliverable |
|---|---|
| M0 ✅ | **Player spike**: mpv with JavaFX overlay and fade. Decision: libmpv (see results below). |
| M1 ✅ | Monorepo skeleton, Docker Compose (PostgreSQL, Keycloak, SeaweedFS), build. |
| M2 ✅ | Library: upload through the web, S3 storage, listing, preview, rename, delete; web login through Keycloak. |
| M3 ✅ | Protocol, player connection, device approval, rest screen with verified cache. |
| M4 ✅ | Event with items (media and text slides), current event, pre-cache on the player with readiness. |
| M5 ✅ | Live control, playback, rest screen, fade. **MVP complete.** |

## M0: player spike

Goal: choose the playback engine for the MediaPlayer with evidence, not assumptions.

Evaluation criteria:
1. Video playback in a fullscreen JavaFX window on a chosen display.
2. JavaFX overlay (text slide / lyrics-like text) composited over video.
3. Fade in/out between items (video → image → text → rest screen).
4. Audio-only playback.
5. Smoothness (no visible tearing, acceptable CPU usage for 1080p).
6. Works on Linux and Windows; packaging effort (bundling native libraries).

### Outcome

**libmpv was chosen.** Through its software render API it met every criterion on Linux:
1080p30 at a steady frame rate, ~1.3 ms per frame to fill the JavaFX buffer, ~30–40% of one
core for the whole process with software decoding (Intel i7-8650U), correct overlay
composition and fades, 4 ms shutdown. The VLCJ comparison was dropped. Windows validation
remains open (see `architecture.md`). Details and pitfalls: `spikes/player-engine/README.md`.

## Live presentation (M5)

- The `live` module keeps the live state of the current event in memory (item on screen, REST / PLAYING / PAUSED / SHOWING). After a server restart the outputs return to the rest screen.
- Commands (`POST /api/live/commands`: PLAY item, NEXT, PREVIOUS, PAUSE, RESUME, REST) go to every approved player with `startAt` 300 ms ahead, so players start together. Leaders, AV technicians and admins control; everyone signed in can follow.
- Web clients receive every change through `GET /api/live/stream` (SSE). The live page shows what is on the outputs, what comes next, progress, the order (click to jump) and each player; keys: → / space / PageDown next, ← / PageUp previous, Esc / B rest screen, P pause (presentation clickers work).
- The player uses two mpv engines: one loops the rest video (muted), the other plays items. Videos cross-fade over the rest screen, audio plays over it, images and text slides fade in. An item not yet cached is downloaded and started late.
- When a video or audio ends, the player returns to the rest screen and reports `ENDED`; the live state follows, and NEXT continues after that item.
- If the server goes away, the player finishes the current item and shows the rest screen. A player connecting during a video joins at the current position.
