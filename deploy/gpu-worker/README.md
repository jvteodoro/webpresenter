# GPU worker

Runs the processing worker on another computer with an NVIDIA GPU. Heavy jobs (separating tracks
and voices, recognizing lyrics) go to their own queue; this worker takes them at once, and the CPU
worker on the main server only after `CPU_HEAVY_JOBS_AFTER` seconds (so it still covers them when
the GPU computer is off or busy).

```
main server (192.168.x / VPN 10.66.0.3)              GPU computer (VPN 10.66.0.1)
  rabbitmq 127.0.0.1:18672 ┐                           worker (CUDA)
  seaweedfs 127.0.0.1:18333┤◀── relay on 10.66.0.3 ◀──  amqp://…@10.66.0.3:5672
                           │    (:5672, :8333)          links sent to http://10.66.0.3:8333 (same Host)
```

Nothing is exposed on the LAN: the relay listens on the VPN address only. If the VPN is down the
relay keeps restarting; the rest of the system is unaffected.

## Main server

In `deploy/.env.prod`:

```
RELAY_ADDRESS=10.66.0.3          # this computer's VPN address
CPU_HEAVY_JOBS_AFTER=600         # the local CPU worker leaves heavy jobs to the GPU for 10 min
# RELAY_AMQP_PORT=5672 / RELAY_S3_PORT=8333: other ports when those are taken on the server
```

```sh
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod --profile remote-workers up -d relay worker
```

## GPU computer

Requirements: Docker with the NVIDIA container runtime, about 15 GB of disk (image and models).
Copy the `worker` and `deploy/gpu-worker` folders (same layout) and create `deploy/gpu-worker/.env`
(`chmod 600`):

```
RABBITMQ_PASSWORD=…              # the main server's RABBITMQ_PASSWORD
SERVER_VPN_ADDRESS=10.66.0.3
DEMUCS_SEGMENT=7                 # 4 GB cards
# RELAY_AMQP_PORT / RELAY_S3_PORT: the same values as on the main server, if changed
```

```sh
cd deploy/gpu-worker
docker compose up -d --build
docker compose logs -f worker     # "worker ready, waiting for jobs"
```
