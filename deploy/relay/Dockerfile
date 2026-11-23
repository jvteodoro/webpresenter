# Forwards the job queue and the media store to workers on other computers, on one address only
# (a VPN such as WireGuard). Built once: no download when it starts.
FROM alpine:3.22.2
RUN apk add --no-cache socat=1.8.0.3-r1 || apk add --no-cache socat
COPY relay.sh /relay.sh
ENTRYPOINT ["/bin/sh", "/relay.sh"]
