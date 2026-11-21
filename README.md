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

Build and test everything Java: `./gradlew build` (the server tests use Testcontainers, so Docker
must be running). Web: `cd web && npm test`.

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
