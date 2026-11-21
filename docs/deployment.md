# Deployment

Production runs as one Docker Compose stack (`deploy/compose.prod.yaml`), separate from the
development infrastructure (`deploy/compose.yaml`), so both can live on the same machine.

## Stack

| Service | Role | Exposed |
|---|---|---|
| `proxy` (Caddy) | HTTPS for browsers (web app, API, live stream, Keycloak under `/auth`, media downloads); HTTP for MediaPlayers and the root certificate | `PUBLIC_PORT`, `S3_BROWSER_PORT`, `PLAYER_PORT` |
| `server` | Central server with the web app bundled | internal |
| `keycloak` | Users and roles (production mode, behind the proxy at `/auth`) | internal |
| `postgres` | Application and Keycloak databases | internal |
| `seaweedfs` | Media store: S3 for downloads, filer for replication | `S3_PUBLIC_PORT`, `FILER_PORT`, `FILER_GRPC_PORT` |
| `replication` (profile) | `weed filer.sync` with another SeaweedFS | outbound only |

Browsers use `PUBLIC_URL` (HTTPS). MediaPlayers use `http://PUBLIC_HOST:PLAYER_PORT` as their
server URL and download media over HTTP from `S3_PUBLIC_PORT`, so they need no certificate.

## HTTPS on the local network

Browsers only allow the cryptography used by the sign-in (Web Crypto, PKCE) on HTTPS pages, so
the panel is served over HTTPS even on a LAN without internet. Caddy runs its own certificate
authority (kept in the `caddy-data` volume) and issues the certificate for `PUBLIC_HOST`.

Install the root certificate **once on every computer, phone or tablet** that opens the panel
(otherwise the browser shows a security warning that has to be accepted). Download it from
`http://PUBLIC_HOST:PLAYER_PORT/webpresenter-ca.crt`, then:

| System | How to trust it |
|---|---|
| Windows | Open the file → Install Certificate → Local Machine → "Trusted Root Certification Authorities". |
| macOS | Open the file → Keychain Access, System keychain → certificate → Trust → "Always Trust". |
| Android | Settings → Security → Encryption & credentials → Install a certificate → CA certificate. |
| iPhone/iPad | Open the link in Safari → install the profile → Settings → General → About → Certificate Trust Settings → enable it. |
| Linux | Copy to `/usr/local/share/ca-certificates/` and run `update-ca-certificates`; Chrome/Firefox may need it imported in their own certificate settings. |
| Firefox (any) | Settings → Privacy & Security → Certificates → View Certificates → Authorities → Import. |

Keep the `caddy-data` volume (back it up): it holds the authority's private key. Moving the
installation to another computer with a copy of that volume keeps every device trusting it.

## First installation

```sh
cp deploy/.env.prod.example deploy/.env.prod   # set addresses and long random passwords
chmod 600 deploy/.env.prod
deploy/build-prod.sh                           # web build + server jar + server image
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod up -d --wait
```

Sign in at `PUBLIC_URL` with `WEBPRESENTER_ADMIN_USER` / `WEBPRESENTER_ADMIN_PASSWORD` (all
roles). Create the other users in the Keycloak console (`PUBLIC_URL/auth/admin`, user
`KEYCLOAK_ADMIN`) and give them realm roles: `admin`, `av-technician`, `worship-leader`, `musician`.

The realm (roles, clients, first user) is imported on the first start only; later changes are
made in the Keycloak console. The `webpresenter-cli` client allows password sign-in for
administration scripts (for example the song import); disable it in the console when not needed.

## Updating

```sh
git pull
deploy/build-prod.sh
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod up -d --wait
```

Database migrations run automatically when the server starts.

## Media replication

Two installations (for example this machine and the church computer) keep their media stores as
live replicas with `weed filer.sync`, **in both directions**: a file uploaded on either side
appears on the other; deletions propagate too. Each side works on its own when the link is down
and catches up when it returns (progress is checkpointed). The first run copies everything that
already exists.

On each side, set `REPLICA_FILER` to the other side's filer and start the profile:

```sh
# deploy/.env.prod on side A
REPLICA_FILER=192.168.0.20:8888      # side B; gRPC = port + 10000
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod --profile replication up -d
```

Run the `replication` service on **one** side only: a single `filer.sync` process already syncs
both ways. If the filer gRPC port is mapped to something other than `port + 10000`, use
`host:port.grpcPort` (for example `10.0.0.5:28888.38888`).

Firewall: the side running `replication` must reach the other side's `FILER_PORT` and
`FILER_GRPC_PORT`. Chunks travel through the filers (`filerProxy`), so volume server ports stay
private.

### Local network

Use the other computer's LAN address in `REPLICA_FILER`. Prefer a fixed IP (DHCP reservation on
the router).

### WireGuard (different networks)

Create a WireGuard tunnel between the two hosts (for example `10.66.0.1` and `10.66.0.2`), with the
filer ports allowed only on the tunnel interface, and use the peer's tunnel address in
`REPLICA_FILER`. Nothing else changes; traffic is encrypted by WireGuard. A WireGuard service can
be added to the stack later; the replication itself does not depend on how the network is built.

### What is not replicated

- **The PostgreSQL database** (media records, events, songs, devices) is not part of media
  replication. Moving an installation means restoring a database backup on the other side
  (`pg_dump`/`pg_restore`); continuous database replication is a later step.
- Concurrent edits of the *same file* on both sides resolve to the last writer. Files are named by
  id, so this does not happen in normal use.

## Backups

- Media: the replica is a live copy; it does not protect against deletion (deletes replicate).
- Database: `docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod exec postgres
  pg_dump -U webpresenter -Fc webpresenter > webpresenter-$(date +%F).dump` (and `keycloak`).
