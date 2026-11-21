#!/bin/sh
# Builds the production server image: web app (Angular) bundled into the server jar.
set -eu
cd "$(dirname "$0")/.."
(cd web && npm ci && npx ng build --configuration production)
./gradlew :server:bootJar -PwithWeb
docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod build server
