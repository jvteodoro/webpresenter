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
| Server → Player | `PREPARE { eventId, items[] }` | Items to pre-cache: mediaId, presigned URL, sha256, size. |
| Player → Server | `CACHE_PROGRESS` / `CACHE_READY` | Pre-cache progress and completion. |
| Server → Player | `PLAY { itemId, startAt, transition }` | Play a specific item at a scheduled instant. |
| Server → Player | `PAUSE` / `RESUME` / `SHOW_REST` | Playback control. |
| Player → Server | `PLAYBACK_STATE { itemId, state, positionMs }` | Playback state reports. |
| Player → Server | `ERROR` | Failures (missing cache, decode error…). |

Design rules:
- The player has no notion of *next* or *previous*. The `live` module computes them and always sends `PLAY` for a specific item. Reordering during a live event therefore requires nothing from the player.
- Device authentication in the MVP uses a server-issued device token. Human users authenticate through Keycloak.
- If the connection is lost, the player finishes the current item, shows the rest screen, and reconnects with backoff.

## Central server modules (MVP subset)

| Module | MVP responsibility |
|---|---|
| `identity` | Validate Keycloak JWTs, map realm roles. |
| `storage` | Upload to the S3 store, generate presigned download URLs. |
| `library` | Media asset registry (type, sha256, size, duration via ffprobe), upload endpoint. |
| `planning` | Event as a flat ordered list of items (media or text slide). |
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
| M2 | Library: upload through the web, S3 storage, listing. |
| M3 | Protocol, player connection, and device approval. |
| M4 | Event with items, pre-cache on the player. |
| M5 | Live control, playback, rest screen, fade. **MVP complete.** |

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
