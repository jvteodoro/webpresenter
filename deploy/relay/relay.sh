#!/bin/sh
# RELAY_ADDRESS: this computer's address on the VPN. RabbitMQ (5672) and S3 (8333) are forwarded
# from it to the services' loopback ports. If the address does not exist yet (VPN down), socat
# fails, the container exits and Docker restarts it: the rest of the system is not affected.
set -eu
: "${RELAY_ADDRESS:?}"
socat "TCP-LISTEN:5672,bind=${RELAY_ADDRESS},reuseaddr,fork" "TCP:127.0.0.1:${AMQP_LOCAL_PORT}" &
socat "TCP-LISTEN:8333,bind=${RELAY_ADDRESS},reuseaddr,fork" "TCP:127.0.0.1:${S3_LOCAL_PORT}" &
echo "relay on ${RELAY_ADDRESS}: 5672 -> RabbitMQ, 8333 -> S3"
wait -n 2>/dev/null || wait
echo "a forwarder stopped; restarting" >&2
exit 1
