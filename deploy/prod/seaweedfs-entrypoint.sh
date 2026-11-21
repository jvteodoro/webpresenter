#!/bin/sh
# Writes the S3 identity from the environment (SeaweedFS only reads it from a file), then starts a
# single-node SeaweedFS: master, volume, filer and S3 gateway.
set -eu
cat > /tmp/s3.json <<JSON
{"identities":[{"name":"webpresenter-server","credentials":[{"accessKey":"${S3_ACCESS_KEY}","secretKey":"${S3_SECRET_KEY}"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
JSON
exec weed server -dir=/data -s3 -s3.port=8333 -s3.config=/tmp/s3.json -filer
