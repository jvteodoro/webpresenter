#!/usr/bin/env bash
# Starts the webpresenter central server for development:
#   1. the infrastructure containers (PostgreSQL, Keycloak, SeaweedFS, RabbitMQ) via Docker Compose;
#   2. the Spring Boot server on port $WEBPRESENTER_PORT (default 8090), in the foreground.
#
# Usage:
#   scripts/start-server.sh              start infrastructure, then the server (Ctrl+C stops the server)
#   scripts/start-server.sh --no-infra   start only the server (infrastructure already running)
#   scripts/start-server.sh --stop       stop the infrastructure containers (data is kept)
#   scripts/start-server.sh -- <args>    pass extra arguments to Gradle, e.g. -- --info
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/deploy/compose.yaml"
port="${WEBPRESENTER_PORT:-8090}"

infra=true
stop=false
gradle_args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-infra) infra=false ;;
        --stop) stop=true ;;
        --) shift; gradle_args=("$@"); break ;;
        -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

compose() {
    if ! docker info >/dev/null 2>&1; then
        echo "error: cannot talk to the Docker daemon. Start Docker and check your permissions." >&2
        exit 1
    fi
    docker compose -f "$compose_file" "$@"
}

if $stop; then
    compose stop
    exit 0
fi

if $infra; then
    echo "==> Starting infrastructure (PostgreSQL, Keycloak, SeaweedFS, RabbitMQ)..."
    compose up -d --wait
fi

if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}$"; then
    echo "error: port $port is already in use. Find the process with: ss -ltnp | grep :$port" >&2
    echo "       or choose another port: WEBPRESENTER_PORT=8091 scripts/start-server.sh" >&2
    exit 1
fi

echo "==> Starting the central server on http://localhost:$port (Ctrl+C to stop)"
cd "$repo_root"
exec ./gradlew :server:bootRun ${gradle_args[@]+"${gradle_args[@]}"}
