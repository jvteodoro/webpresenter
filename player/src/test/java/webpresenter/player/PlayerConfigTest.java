package webpresenter.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerConfigTest {

    @Test
    void readsCommandLineOptions() {
        PlayerConfig config = PlayerConfig.from(List.of("--server-url=http://192.168.0.10:8080", "--screen=1", "--windowed"));

        assertEquals(URI.create("http://192.168.0.10:8080"), config.serverUrl());
        assertEquals(1, config.screen());
        assertTrue(config.windowed());
    }
}
