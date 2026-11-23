#!/bin/sh
# RELAY_ADDRESS: this computer's address on the VPN. RabbitMQ (RELAY_AMQP_PORT) and S3 (RELAY_S3_PORT) are forwarded
# from it to the services' loopback ports. If the address does not exist yet (VPN down), socat
# fails, the container exits and Docker restarts it: the rest of the system is not affected.
set -eu
: "${RELAY_ADDRESS:?}"
socat "TCP-LISTEN:${RELAY_AMQP_PORT:-5672},bind=${RELAY_ADDRESS},reuseaddr,fork" "TCP:127.0.0.1:${AMQP_LOCAL_PORT}" &
socat "TCP-LISTEN:${RELAY_S3_PORT:-8333},bind=${RELAY_ADDRESS},reuseaddr,fork" "TCP:127.0.0.1:${S3_LOCAL_PORT}" &
echo "relay on ${RELAY_ADDRESS}: ${RELAY_AMQP_PORT:-5672} -> RabbitMQ, ${RELAY_S3_PORT:-8333} -> S3"
wait -n 2>/dev/null || wait
echo "a forwarder stopped; restarting" >&2
exit 1
