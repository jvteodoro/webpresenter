package webpresenter.player.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the central server is: a fixed URL ({@code --server-url}) or found on the LAN by listening
 * to the server's beacon (UDP broadcasts with its current addresses), so a changed address never
 * needs reconfiguring: when the connection fails the address is looked up again.
 */
public abstract sealed class ServerAddress {

    /** The server's base URL (http://host:port), or null when it cannot be found right now. */
    public abstract URI resolve();

    /** The last connection attempt failed: an automatic address is looked up again next time. */
    public void failed() {}

    /** For people: the URL, or what is being done to find it. */
    public abstract String describe();

    /** Resolves a link from the server (absolute, or relative like "/s3/…") against the current address. */
    public URI link(String url) {
        URI uri = URI.create(url);
        if (uri.isAbsolute()) {
            return uri;
        }
        URI base = resolve();
        if (base == null) {
            throw new IllegalStateException("server address unknown");
        }
        return base.resolve(uri);
    }

    public static ServerAddress fixed(URI url) {
        return new Fixed(url);
    }

    public static ServerAddress discovered(int beaconPort) {
        return new Discovered(beaconPort);
    }

    static final class Fixed extends ServerAddress {
        private final URI url;

        Fixed(URI url) {
            this.url = url;
        }

        @Override
        public URI resolve() {
            return url;
        }

        @Override
        public String describe() {
            return url.toString();
        }
    }

    static final class Discovered extends ServerAddress {
        private static final System.Logger LOG = System.getLogger(ServerAddress.class.getName());
        private static final Duration LISTEN = Duration.ofSeconds(8);

        private final int beaconPort;
        private final JsonMapper json = new JsonMapper();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private volatile URI current;

        Discovered(int beaconPort) {
            this.beaconPort = beaconPort;
        }

        @Override
        public URI resolve() {
            URI known = current;
            if (known != null) {
                return known;
            }
            URI found = listen();
            current = found;
            if (found != null) {
                LOG.log(System.Logger.Level.INFO, "server found on the network at {0}", found);
            }
            return found;
        }

        @Override
        public void failed() {
            current = null;
        }

        @Override
        public String describe() {
            URI known = current;
            return known != null ? known.toString() : "procurando o servidor na rede…";
        }

        /** Listens to the beacon and returns the first announced address that answers. */
        private URI listen() {
            Set<URI> tried = new LinkedHashSet<>();
            Instant deadline = Instant.now().plus(LISTEN);
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(beaconPort));
                byte[] buffer = new byte[4096];
                while (Instant.now().isBefore(deadline)) {
                    socket.setSoTimeout((int) Math.max(1, Duration.between(Instant.now(), deadline).toMillis()));
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    for (URI candidate : candidates(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8),
                            packet.getAddress().getHostAddress())) {
                        if (tried.add(candidate) && answers(candidate)) {
                            return candidate;
                        }
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot listen for the server on UDP {0}: {1}", beaconPort, e.getMessage());
            }
            return null;
        }

        /** The sender first (it is reachable from here), then the other announced addresses. */
        List<URI> candidates(String payload, String sender) {
            List<URI> result = new ArrayList<>();
            try {
                JsonNode beacon = json.readTree(payload);
                if (!"webpresenter".equals(beacon.path("service").asString(""))) {
                    return result;
                }
                int port = beacon.path("playerPort").asInt(80);
                result.add(URI.create("http://" + sender + ":" + port));
                for (JsonNode address : beacon.path("addresses")) {
                    URI uri = URI.create("http://" + address.asString() + ":" + port);
                    if (!result.contains(uri)) {
                        result.add(uri);
                    }
                }
            } catch (RuntimeException e) {
                // not a beacon
            }
            return result;
        }

        private boolean answers(URI base) {
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(base.resolve("/actuator/health"))
                        .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
                return response.statusCode() == 200;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
