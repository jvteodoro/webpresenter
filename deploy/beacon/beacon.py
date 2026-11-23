"""
Announces this computer to the LAN, whatever its current address:

  - mDNS: the name PUBLIC_NAME (e.g. webpresenter.local) resolves to the current IPv4 addresses,
    and the service _webpresenter._tcp points at the MediaPlayer port (browsers, players);
  - UDP broadcast every few seconds on BEACON_PORT: {"service": "webpresenter", "version": 1,
    "addresses": [...], "playerPort": …, "panelPort": …} — MediaPlayers listen to it.

Addresses are checked every few seconds; when they change (another network, DHCP), both
announcements follow. Runs with host networking to see the real interfaces.
"""
import ipaddress
import json
import logging
import os
import socket
import time

import ifaddr
from zeroconf import IPVersion, ServiceInfo, Zeroconf

NAME = os.environ.get("PUBLIC_NAME", "webpresenter.local").rstrip(".")
PANEL_PORT = int(os.environ.get("PUBLIC_PORT", "443"))
PLAYER_PORT = int(os.environ.get("PLAYER_PORT", "80"))
BEACON_PORT = int(os.environ.get("BEACON_PORT", "47800"))
INTERVAL = float(os.environ.get("BEACON_INTERVAL", "3"))
# Virtual interfaces of containers and tunnels are not where people are.
# Tunnels (WireGuard) carry no multicast and are not where the players are.
IGNORED_PREFIXES = tuple(os.environ.get("BEACON_IGNORE", "lo,docker,br-,veth,virbr,podman,cni,flannel,wg,tun,tailscale").split(","))

log = logging.getLogger("beacon")


def lan_addresses() -> list[tuple[str, str]]:
    """(address, broadcast) of the IPv4 LAN interfaces."""
    result = []
    for adapter in ifaddr.get_adapters():
        if adapter.nice_name.startswith(IGNORED_PREFIXES):
            continue
        for ip in adapter.ips:
            if not isinstance(ip.ip, str):
                continue  # IPv6
            address = ipaddress.ip_address(ip.ip)
            if address.is_loopback or address.is_link_local:
                continue
            network = ipaddress.ip_network(f"{ip.ip}/{ip.network_prefix}", strict=False)
            broadcast = str(network.broadcast_address) if network.prefixlen < 31 else None
            result.append((ip.ip, broadcast))
    return sorted(result)


def service_info(addresses: list[str]) -> ServiceInfo:
    return ServiceInfo(
        "_webpresenter._tcp.local.",
        "webpresenter._webpresenter._tcp.local.",
        addresses=[socket.inet_aton(a) for a in addresses],
        port=PLAYER_PORT,
        properties={"panelPort": str(PANEL_PORT), "name": NAME, "version": "1"},
        server=NAME + ".",
    )


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    zeroconf = None
    sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sender.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    announced: list[str] = []
    info = None
    try:
        while True:
            current = lan_addresses()
            addresses = [a for a, _ in current]
            if addresses != announced:
                log.info("addresses: %s", ", ".join(addresses) or "none")
                if zeroconf is not None:
                    if info is not None:
                        zeroconf.unregister_service(info)
                    zeroconf.close()
                    zeroconf, info = None, None
                if addresses:
                    # Only on the LAN interfaces (multicast on tunnels fails).
                    zeroconf = Zeroconf(interfaces=addresses, ip_version=IPVersion.V4Only)
                    info = service_info(addresses)
                    zeroconf.register_service(info, allow_name_change=True)
                    log.info("announcing %s (mDNS) → %s", NAME, ", ".join(addresses))
                announced = addresses
            payload = json.dumps({"service": "webpresenter", "version": 1, "name": NAME, "addresses": addresses,
                                  "playerPort": PLAYER_PORT, "panelPort": PANEL_PORT}).encode()
            targets = {b for _, b in current if b} | {"255.255.255.255"}
            for target in targets:
                try:
                    sender.sendto(payload, (target, BEACON_PORT))
                except OSError as e:
                    log.debug("broadcast to %s: %s", target, e)
            time.sleep(INTERVAL)
    finally:
        if zeroconf is not None:
            if info is not None:
                zeroconf.unregister_service(info)
            zeroconf.close()


if __name__ == "__main__":
    main()
