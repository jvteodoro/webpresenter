# Deployment

Production runs as one Docker Compose stack (`deploy/compose.prod.yaml`), separate from the
development infrastructure (`deploy/compose.yaml`), so both can live on the same machine.

## Stack

| Service | Role | Exposed |
|---|---|---|
| `proxy` (Caddy) | Single public address: web app, API, player WebSocket, live stream, Keycloak under `/auth` | `PUBLIC_PORT` |
| `server` | Central server with the web app bundled | internal |
| `keycloak` | Users and roles (production mode, behind the proxy at `/auth`) | internal |
| `postgres` | Application and Keycloak databases | internal |
| `seaweedfs` | Media store: S3 for downloads, filer for replication | `S3_PUBLIC_PORT`, `FILER_PORT`, `FILER_GRPC_PORT` |
| `replication` (profile) | `weed filer.sync` with another SeaweedFS | outbound only |

Players use `PUBLIC_URL` as their server URL and download media from `S3_PUBLIC_URL`.

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
