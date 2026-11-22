# webpresenter

Distributed multimedia presentation for live events, focused on church services. A central
server on the venue's network controls MediaPlayers attached to screens, projectors, and audio
devices, and operators use a web app (and later an Android app).

Documentation: [`docs/business.md`](docs/business.md), [`docs/architecture.md`](docs/architecture.md),
[`docs/mvp.md`](docs/mvp.md).

## Repository layout

| Path | Contents |
|---|---|
| `protocol/` | Server ↔ player message contract (Java, no dependencies). |
| `server/` | Central server: Spring Boot + Spring Modulith. |
| `player/` | MediaPlayer: JavaFX + libmpv. |
| `web/` | Web frontend: Angular. |
| `deploy/` | Docker Compose infrastructure and Keycloak realm. |
| `spikes/` | Throwaway prototypes that informed decisions. |

## Requirements

- JDK 25
- Node.js 24+ and npm
- Docker with Compose
- ffmpeg/ffprobe (the server inspects uploads with ffprobe; the server tests generate media with ffmpeg)
- libmpv (for the player): `media-video/mpv` with the `libmpv` USE flag on Gentoo,
  `libmpv2` on Debian/Ubuntu

## Development

```sh
# 1. Infrastructure: PostgreSQL :5432, Keycloak :8180, SeaweedFS S3 :8333
docker compose -f deploy/compose.yaml up -d

# 2. Central server on :8090
./gradlew :server:bootRun

# 3. Web app on :4200
cd web && npm install && npm start

# 4. MediaPlayer (windowed for development)
./gradlew :player:run --args="--windowed --server-url=http://localhost:8090"
```

### Presenting an event

1. **Biblioteca**: upload videos, audio and images.
2. **Eventos**: create the event, add media and text slides, order them, then **Preparar nos players**.
3. **Ao vivo**: control the outputs (→ / PageDown next, ← / PageUp previous, Esc rest screen, P pause).

### Running a MediaPlayer

```sh
./gradlew :player:installDist
player/build/install/player/bin/player --server-url=http://<server>:8090 [--windowed] [--data-dir=<dir>]
```

On first start the player shows a pairing code; approve it in **Dispositivos** in the web app.
Its identity (device id and token) and media cache live in the OS's config and cache folders
(`~/.config/webpresenter-player`, `~/.cache/webpresenter-player` on Linux; `%APPDATA%` and
`%LOCALAPPDATA%` on Windows), or under `--data-dir`. Delete the config folder to give a copied
installation a new identity. `Shift+Esc` closes the player.

#### Windows installer

After cloning the repository on a 64-bit Windows PC, open PowerShell in the project folder and run
the preparation/build script:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\build-windows-player.ps1
```

The script uses WinGet to install any missing JDK 25, WiX Toolset 3, and 7-Zip tools, enables
Windows .NET Framework 3.5 (required by WiX), downloads the x64 libmpv development and runtime
archives, and builds the self-contained installer. It needs internet access and will request
administrator approval if WiX needs installation. You can rerun it safely after an interrupted
setup; already installed tools are detected and skipped. The installer is written to
`player\build\windows-installer`; it includes its own Java runtime, so Java is not needed on the
event computer. Installation is system-wide and may request administrator approval. After
installing, launch the player with the central server's LAN address, for example:

```powershell
& "C:\Program Files\WebPresenter MediaPlayer\WebPresenter MediaPlayer.exe" `
  --server-url=http://192.168.1.10:8090
```

Replace the address with the computer hosting the central server. Approve the new device in
**Dispositivos**; the player caches the event media locally before playback.

Build and test everything Java: `./gradlew build` (the server tests use Testcontainers, so Docker
must be running). Web unit tests: `cd web && npm test`.

End-to-end tests (Playwright, Chromium and Firefox) run against the real stack: start the Compose
infrastructure and the server, then `cd web && npx playwright install chromium firefox` (once) and
`npm run e2e`. The Angular dev server is started automatically if needed.

### Development users (Keycloak realm `webpresenter`)

| User / password | Roles |
|---|---|
| `admin` / `admin` | all |
| `tecnico` / `tecnico` | av-technician |
| `lider` / `lider` | worship-leader |
| `musico` / `musico` | musician |

Keycloak admin console: http://localhost:8180 (`admin` / `admin`).

Getting a token for API tests (development-only client):

```sh
TOKEN=$(curl -s -d client_id=webpresenter-dev-cli -d grant_type=password \
  -d username=lider -d password=lider \
  http://localhost:8180/realms/webpresenter/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/me
```

All credentials above, and the S3 keys in `deploy/seaweedfs/s3.json`, are for development only.
