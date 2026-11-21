package webpresenter.player.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ServerConnectionTest {

    @Test
    void derivesTheWebSocketEndpointFromTheServerUrl() {
        assertEquals(URI.create("ws://192.168.0.10:8090/ws/player"),
                ServerConnection.toWebSocket(URI.create("http://192.168.0.10:8090")));
        assertEquals(URI.create("ws://server:8090/ws/player"),
                ServerConnection.toWebSocket(URI.create("http://server:8090/")));
        assertEquals(URI.create("wss://igreja.example/ws/player"),
                ServerConnection.toWebSocket(URI.create("https://igreja.example")));
    }
}
