# webpresenter worker

Processes the jobs queued by the server: **YouTube import** (yt-dlp) and **vocal separation**
(Demucs, to generate a playback from a sung recording). Runs as its own container in production
(`deploy/compose.prod.yaml`, service `worker`); several workers can share the queue.

## Development

```sh
cd worker
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python -e ".[separation]"
.venv/bin/webpresenter-worker          # RabbitMQ from deploy/compose.yaml on localhost:5672
```

Requirements on the host: `ffmpeg`, and a JavaScript runtime for YouTube (`node`, `deno` or `bun`).
Demucs downloads its model (~80 MB) on first use into `TORCH_HOME` (default `~/.cache/torch`).
It runs on CPU (several minutes per song) or on an NVIDIA GPU when PyTorch has CUDA.

| Variable | Default | |
|---|---|---|
| `RABBITMQ_URL` | `amqp://webpresenter:webpresenter@localhost:5672/%2F` | broker |
| `WORK_DIR` | system temp | scratch files (removed after each job) |
| `DEMUCS_MODEL` | `htdemucs` | `htdemucs_ft` is better and 4× slower |
| `DEMUCS_DEVICE` | `cuda` if available | `cpu` or `cuda` |

## Protocol

Plain JSON (UTF-8) messages.

**`webpresenter.jobs`** (durable queue, server → worker). The worker takes one job at a time
(`basic_get`) and acknowledges it at once: jobs can outlast RabbitMQ's delivery timeout. A worker
that dies stops reporting and the server fails the job after 15 minutes (it can be retried).

```json
{ "jobId": "…", "attempt": 1, "type": "YOUTUBE_IMPORT",
  "params": { "url": "https://…", "target": "BACKGROUND_MUSIC", "format": "AUDIO", "title": null },
  "inputUrl": null, "outputUrl": "http://…presigned PUT…" }
{ "jobId": "…", "attempt": 1, "type": "SEPARATE_PLAYBACK",
  "params": { "songId": "…", "recordingId": "…", "mediaId": "…" },
  "inputUrl": "http://…presigned GET…", "outputUrl": "http://…presigned PUT…" }
```

**`webpresenter.job-events`** (durable queue, worker → server): progress at most once a second
and at least every 30 s (heartbeat), then the outcome. The result file is uploaded to `outputUrl`
**before** `DONE`; the server registers it in the library (and the song).

```json
{ "jobId": "…", "attempt": 1, "status": "RUNNING", "progress": 40, "message": "Separando as vozes 40%" }
{ "jobId": "…", "attempt": 1, "status": "DONE", "progress": 100, "filename": "playback.m4a", "title": null }
{ "jobId": "…", "attempt": 1, "status": "FAILED", "message": "Não foi possível baixar: Video unavailable" }
```

Messages are in Portuguese: they are shown in the queue page.

**`webpresenter.job-control`** (fanout exchange, server → every worker): `{"jobId": "…",
"action": "CANCEL"}`. The worker running that job stops it (yt-dlp is interrupted, Demucs is killed).
