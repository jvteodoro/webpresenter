# Roadmap after the MVP

Design and phases for the second round of features: library v2, ingestion pipeline (YouTube,
Demucs) with a job queue, background music with a mixer, operation and administration modes,
calendar and liturgy templates, clock and timer.

## 1. Library v2

### Concepts

| Concept | What it is | Examples |
|---|---|---|
| **Album** | An ordered, named set of songs with a cover. Imported (LouvorJA) or created by people. | "Hinário Adventista", "CD Jovem 2012" |
| **Playlist** | An ordered list of songs and library media made by people, for events or as background music. | "Ofertório", "Música ambiente - recepção" |
| **Song** | As in `songs.md`. A song not in any album is a *loose song*. | |
| **Background music** (fundo musical) | Library audio meant to play softly when nothing else sounds. | instrumental piano |
| **Sound effect** | Short library audio used by timers and cues. | bell, chime |

Albums are the current song *collections*, extended with a cover, a description and an owner
(`IMPORT` or `MANUAL`). The LouvorJA album covers (`media/covers/*.bmp`) are imported as library
images. Library media gains two purposes: `BACKGROUND_MUSIC` and `SOUND_EFFECT`.

### Library page sections

Álbuns (cover grid → album page with tracks) · Playlists · Músicas avulsas · Fundos musicais ·
Efeitos sonoros · Mídia (today's general media).

As implemented (phase A):

- Albums are grouped in the grid by their group (LouvorJA category, or one typed by people).
  Imported albums keep their tracks and numbers (hymnal numbers); people can change their name,
  description and cover. Albums made by people have their tracks added, reordered and removed.
- Covers are library images with purpose `COVER`; a cover in use cannot be deleted.
- Playlists hold songs (with the recording to play; songs without audio are refused) and library
  audio/video; worship leaders, AV technicians and admins edit them.
- `GET /api/media?purpose=A,B` lists several purposes; uploads take a `purpose`.
- The LouvorJA import tool converts the album covers (BMP → JPEG) and sets them
  (`--covers-only` applies only that step).

## 2. Ingestion and processing pipeline

### Job queue

Every long task is a **job** stored by the server (`QUEUED`, `RUNNING`, `DONE`, `FAILED`,
`CANCELLED`, with progress 0–100 and a message), executed by **Python workers** through
**RabbitMQ**, as planned in `architecture.md`.

```
web ──REST──▶ server ──job message──▶ RabbitMQ ──▶ worker (yt-dlp, ffmpeg, Demucs)
  ▲             │  ▲                                  │
  └──SSE────────┘  └──────progress / result───────────┘  files: S3 (SeaweedFS)
```

- Workers download inputs from S3 (presigned URL), write outputs to S3 (presigned upload) and
  report progress and results through RabbitMQ; the server registers results in the library.
- A **Jobs** page (and a badge in the header) shows the queue with live progress; jobs can be
  cancelled and failed ones retried.

### First job types

| Job | Input | Output |
|---|---|---|
| `YOUTUBE_IMPORT` | URL, target (media, background music, sound effect, new song), audio-only or video | library asset (and song with a SUNG recording when target is a song) |
| `SEPARATE_PLAYBACK` | a song's SUNG recording | a PLAYBACK recording (`source=PIPELINE`) made with Demucs (vocals removed), timing copied from the sung recording |

Later job types (see `songs.md`): transcription, lyrics alignment, segmentation.

Demucs runs on CPU by default (several minutes per song); a GPU, when present, is used
automatically. Workers run as their own container in the production stack.

As implemented (phase B): module `jobs` (table `job`, REST under `/api/jobs`, SSE stream
`/api/jobs/stream`), message protocol in `worker/README.md`. The server stays healthy without
RabbitMQ (jobs then fail at once with a clear message and can be retried). Results are uploaded by
the worker to a staging key through a presigned PUT and registered by the server through the
library (`MediaImporter`) and songs (`SongPipeline`). The web app has the queue page (`/fila`,
badge with active jobs), "Importar do YouTube" in the library sections and the queue, and
"Gerar playback sem voz" on song recordings with vocals.

## 3. Background music and mixer

### Resolution

Background music plays when nothing else produces sound: rest screen, text slides, images, songs
without audio, clock and timer items. The source can be **one track or a playlist** (looped,
optionally shuffled). The effective source for the item on screen is the first defined of:

1. the **item**'s background music (or "silence"),
2. its **scene**'s,
3. the **event**'s,
4. the **platform default** (system settings).

A **scene** is a named group of consecutive event items ("Escola Sabatina", "Culto Divino"),
also used by the timer (scene end time) and the operation view.

### Mixing on the player

- A third playback engine on the player plays background music. When an item with its own sound
  starts (video, audio, song with audio), background music **fades out** (or ducks to a low
  level, configurable); when it ends, background music **fades back in**.
- **System settings**: fade-in and fade-out durations, duck level, and the mixer defaults.
- **Mixer**: separate volume channels — *media* (videos, audio, songs), *background music*,
  *effects* (timer signals) and *master* — set per platform and adjustable live in operation
  mode; changes reach players immediately.

As implemented (phase C):

- Scenes are `SCENE` marker items in the event's list (name, optional start/end time, background
  music); the items after a marker belong to it until the next one. Markers are not presented:
  the plan given to players and to the live state leaves them out, and each item carries its
  scene. The live page groups items under their scene.
- `BackgroundSource` (module `playlists`): `PLAYLIST`, `MEDIA` or `SILENCE`; null inherits.
  Planning resolves item → scene → event; devices completes it with the platform default
  (module `settings`) and expands it into tracks (`BackgroundMusic` in `PREPARE`: a key, the
  tracks, shuffle). `PREPARE.rest` is the rest screen's (event → platform).
- `MIXER` message (levels, fades, duck percent) after `HELLO`/approval and on every change.
  Settings page (`/configuracoes`) for admins and AV technicians; live faders (operation mode)
  also for worship leaders.
- Player: an audio-only mpv plays the background in a loop; the same key on the next item keeps
  it going; another key fades out, then in; items with sound duck it (or pause it at 0 %); the
  media channel scales the item engine. mpv's volume is perceptual (cubic), so levels behave like
  faders on a desk. Verified on a real player: plays at rest, pauses during an audio item and
  returns with a fade, continues across slides with the same source, stops for silence.
- Older players ignore `MIXER` and the new fields (no background music until updated).

## 4. Operation and administration modes

- **Operation mode**: for use during the event. Live control first (big controls, current/next,
  scene and item list, timer, mixer faders, quick background music change). Default for worship
  leaders and musicians.
- **Administration mode**: events, calendar, liturgy, songs, library, jobs, devices, settings.
  Default for admins and AV technicians.
- A mode switch in the header; the mode decides navigation and the start page. The default
  follows the roles (admins and AV technicians: administration; others: operation) and the
  choice is remembered per browser. Permissions do not depend on the mode.

## 5. Calendar and liturgy

- **Calendar**: month/week view of events. Weekdays with a liturgy template show the expected
  event even before it is created.
- **Liturgy templates**: an ordered list of **slots**, grouped in scenes, assigned to weekdays
  (e.g. Saturday). A slot is either **fixed** (always the same content: "Informativo Mundial das
  Missões" video, "Oração" slide) or **variable** (a kind and a label: "Hino escola sabatina 1",
  to be chosen each week). Scenes carry their planned times (Escola Sabatina 9:30–10:10).
- **Event from a template**: opening a date creates the event from the template: fixed slots
  filled, variable slots empty and highlighted. The event is *ready* when every variable slot is
  filled. Later template changes do not alter existing events.

## 6. Clock and timer

- **Clock**: a full-screen clock, usable as the rest screen (device setting) or as an event item.
- **Timer item**: counts down to a target — the end time of its scene (9:30–10:10 → until 10:10),
  a fixed time, or a duration — and counts up after it. **Signals** at marks (e.g. 5 min left,
  1 min left, end) play a library sound (sound effect or any song/audio) through the *effects*
  channel. Visible on the players' output and in operation mode.

## Phases

Status: phases A and C implemented; phase B implemented and tested with a simulated worker (real yt-dlp/Demucs run pending: network); D–E to do.

| Phase | Content |
|---|---|
| **A** | Library v2: albums with imported covers, album creation, playlists, background music and sound effect purposes, library sections. Mode switch (operation/administration) with navigation. Player screen shows its server; approve a player by code. |
| **B** | Job queue (RabbitMQ, jobs API, Jobs page with live progress) and Python worker: YouTube import (yt-dlp) and Demucs playback generation. |
| **C** | Scenes in events; background music resolution (platform, event, scene, item); system settings (fades, duck level); player background engine and mixer channels; live mixer in operation mode. |
| **D** | Clock (rest screen and item) and timer items with signals. |
| **E** | Calendar and liturgy templates; events from templates with fixed and variable slots. |
