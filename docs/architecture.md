# Architecture

This document records the architectural decisions for the system. See `business.md` for the product vision and user profiles.

## Decision summary

| Topic | Decision |
|---|---|
| Deployment | Self-hosted on the venue's local network (LAN). Live events do not depend on internet access. |
| Tenancy | Single organization per installation. |
| Packaging | Docker Compose for all server-side components. The MediaPlayer is a native desktop application. |
| Backend | Java 25, Spring Boot, Spring Modulith (modular monolith) plus microservices where distribution is required. |
| Frontend | Angular, component-oriented. Android delivered as PWA first, Capacitor when native features are needed. |
| Identity | Keycloak (registration, fixed global roles, token generation). |
| Database | PostgreSQL (shared by the application and Keycloak, separate databases). |
| Media storage | MinIO (S3 API) from day one. |
| Real-time communication | WebSocket for frontend clients and MediaPlayers. |
| Job queue | RabbitMQ between the central server and the media workers. |
| Media processing | Python workers (yt-dlp, ffmpeg, Whisper/WhisperX, Demucs). |
| MediaPlayer | Java + JavaFX + native playback (VLCJ/libVLC or mpv, pending spike). |
| Media delivery to players | Mandatory pre-cache before the event. |
| Player behavior on server loss | Finish the current item, show the rest screen, reconnect. |
| Synchronization | Frame-accurate within one MediaPlayer host. About 100 ms tolerance across hosts (NTP-scheduled start). |
| Player registration | Automatic LAN discovery via mDNS plus mandatory operator approval. |
| Lyrics and chords format | Extended ChordPro with timestamps. Sheet music as attachments. |

## Deployment topology

Everything runs inside the venue's network. A single machine hosts the server stack, and one or more machines connected to screens and audio devices run the MediaPlayer.

```
                         Venue LAN
 ┌─────────────────────── Server host (Docker Compose) ───────────────────────┐
 │                                                                             │
 │  ┌──────────────────┐   ┌──────────┐   ┌────────────┐   ┌──────────────┐    │
 │  │  Central server  │──▶│ Keycloak │   │ PostgreSQL │   │    MinIO     │    │
 │  │ (Spring Modulith)│──────────────────▶│            │   │    (S3)      │    │
 │  └───┬───────┬──────┘   └──────────┘   └────────────┘   └──────▲───────┘    │
 │      │       │                                                 │            │
 │      │   ┌───▼──────┐   ┌─────────────────────────┐            │            │
 │      │   │ RabbitMQ │◀─▶│ Media workers (Python)  │────────────┘            │
 │      │   └──────────┘   │ yt-dlp, ffmpeg, Whisper,│                         │
 │      │                  │ Demucs (optional GPU)   │                         │
 │      │                  └─────────────────────────┘                         │
 └──────┼──────────────────────────────────────────────────────────────────────┘
        │ WebSocket + REST                       ▲ presigned HTTP downloads
        ├──────────────────────┐                 │
 ┌──────▼───────┐       ┌──────▼──────────┐      │
 │ Web / PWA /  │       │  MediaPlayer(s) │──────┘
 │ Android      │       │  native app     │──▶ screens, projectors, audio interfaces
 └──────────────┘       └─────────────────┘
```

- Remote access (for example, preparing an event from home) is out of scope for now. It can later be added through a tunnel or VPN without changing the architecture.
- mDNS does not cross Docker's bridge network, so the component responsible for the mDNS announcement runs with `network_mode: host`.
- The MediaPlayer is not containerized because it needs direct access to displays and audio devices.

## Central server (modular monolith)

The central server is a Spring Boot application organized with Spring Modulith. Module boundaries are verified by Modulith, and modules communicate through application events instead of direct calls wherever possible.

Authentication and access control always live in the central server. Components that may need to be distributed (media storage, media processing, media playback) are separate services.

### Modules

| Module | Responsibility |
|---|---|
| `identity` | Keycloak integration (OAuth2 resource server), mapping of global roles, user lookup. |
| `library` | Media assets and their metadata, songs (extended ChordPro, lyric timings, sheet music attachments), rest screen assets. |
| `ingestion` | Creates processing jobs on RabbitMQ, tracks progress and status, registers results in `library`. |
| `planning` | Event (service) planning: steps, groups, titles, text slides, event roles (prayer, tithing, announcements…) and the people responsible for them. |
| `live` | Live execution of an event as a state machine (current item, next item, skip, jump, reorder, replace) and real-time broadcast through WebSocket. |
| `devices` | MediaPlayer registry: mDNS announcement, pending/approved devices, credential issuance, configuration of screens and audio outputs, rest screen and transitions, cache status. |
| `storage` | Abstraction over the S3 API. Uploads and presigned URLs for direct downloads from MinIO. |

Example event flow: when an event is published in `planning`, `devices` reacts and instructs each relevant MediaPlayer to pre-cache the event's media.

### Roles

Roles are fixed and global, defined as Keycloak realm roles:

- **Admin**: system configuration, users, devices.
- **AV Technician**: manages the media library, ingestion, and devices.
- **Worship Leader**: plans and controls live events (reorder, skip, jump, replace items).
- **Musician**: read-only view of the live event, lyrics, chords, and sheet music.

Event roles (prayer, tithing collection, announcements…) are informational assignments in the `planning` domain and do not grant permissions.

## Real-time communication

- WebSocket is the single real-time protocol for the web frontend, the Android app, and MediaPlayers.
- **The server is the single source of truth for live state.** Clients and players only send commands and receive state updates.
- Commands sent to MediaPlayers carry a scheduled start time (`startAt`) based on NTP-synchronized clocks. Players start playback at that instant, which gives about 100 ms alignment across hosts.
- Screens and audio outputs driven by the same MediaPlayer host are frame-accurate, because a single process controls them. Tight cross-host lip-sync (audio on one machine, video on another) is out of scope.

## MediaPlayer

The MediaPlayer handles media playback. It is a desktop application (not only a background service) because it must render playback windows on the host. It runs on Unix-like systems and Windows.

- **Technology**: Java + JavaFX for windowing and overlays (lyrics, slides, transitions) on top of native playback through VLCJ (libVLC) or mpv. The choice between them is pending a spike (see *Open items*).
- **Registration**: the central server announces itself on the LAN via mDNS. A new player discovers the server and shows up as *pending* in the admin panel. An operator approves and names it, and only then does the server issue the device credential. Each player has a unique, persistent ID.
- **Configuration**: all parameters (screens, audio outputs, rest screen, transitions) are managed by the central server and pushed to the player.
- **Multiple outputs**: a player can take control of one or more screens and audio outputs and play different media on different screens.
- **Pre-cache**: when an event is published or opened, the player downloads all of its items to a local cache through presigned URLs directly from MinIO and reports progress. An event is *ready* only when every relevant player has confirmed its cache. Items added during a live event are downloaded on demand, and the UI shows when they become available.
- **Rest screen**: a configurable image, video, or animation kept in the local cache. When the central server changes it, it sends the new asset and parameters to the player.
- **Transitions**: configurable fade in, fade out, and others.
- **Failure behavior**: if the connection to the central server is lost, the player finishes the current item, shows the rest screen, and keeps trying to reconnect.

## Media storage

Media is stored in MinIO through the S3 API from the start. Because the code depends only on the S3 API, the storage can later move to a dedicated NAS or a distributed S3-compatible store (for example, SeaweedFS or Garage) by changing configuration, not code.

The main media type is music. It is usually acquired from YouTube, where lyrics are embedded in the video, so their size, color, and position cannot be changed. To support that flexibility, the system stores:

- the audio (with vocals and/or backing track);
- the lyrics with timestamps, enabling real-time rendering of the lyrics synchronized with the music.

### Lyrics and chords format

Songs are stored in **ChordPro**, extended with per-line (and optionally per-word) timestamps for synchronization with the audio. Sheet music is stored as attachments (PDF or MusicXML).

## Media processing pipeline

Media processing runs in Python workers deployed as their own container (GPU optional), consuming jobs from RabbitMQ and reading/writing media in MinIO. The central server's `ingestion` module orchestrates the jobs and tracks their status.

Pipeline stages:

1. **Acquisition**: upload of a video file of any format, or a YouTube link processed by yt-dlp.
2. **Audio extraction** with ffmpeg.
3. **Lyrics timing**: Whisper-based transcription, preferably forced alignment (for example, WhisperX) against known lyrics.
4. **Backing track generation** with Demucs (optional).
5. **Configurable lyrics rendering**: lyrics are rendered at playback time by the MediaPlayer, not burned into the video.

In the first iteration, ingested videos are added to the library and played as they were uploaded. Lyrics processing comes later.

## Client side

Access is through the Angular web frontend or the Android app. The Android app is the same Angular code base, delivered as a PWA first and packaged with Capacitor when native features are required. The code base is heavily component-oriented so new frontend features are easy to build.

### Initial business flows

#### Media ingestion
The operator adds video files through the interface, either by uploading a video file of any format or by sending a YouTube link for yt-dlp to process. The file is added to the media library. Initially it is not processed into a lyrics video and is played as it was sent.

#### Event configuration
The operator organizes the event's steps, including audiovisual files from the library or new uploads. The operator can add text slides to show on screen. Steps can have titles and can be grouped and organized so it is easy to understand what each step or set of steps is for. The operator can also create event roles (prayer, tithing collection, announcements…) and set who is responsible for each one.

#### Event execution
The event is executed and each item is played, with transitions if configured. The worship leader can skip items, jump to items, reorder items, or replace them. Musicians and singers follow the items and see what comes next.

## Open items

- **Playback engine spike**: compare VLCJ and mpv for rendering JavaFX overlays (lyrics, transitions) over video, on multiple screens, on Linux and Windows.
- **Lyrics alignment quality**: evaluate WhisperX forced alignment on worship music, with and without Demucs vocal isolation.
- **Extended ChordPro specification**: define the exact syntax for line and word timestamps.
- **Live edits and pre-cache**: define the UX when an item is added during a live event and its media is not cached yet.
