package webpresenter.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerConfigTest {

    @Test
    void readsCommandLineOptions() {
        PlayerConfig config = PlayerConfig.from(
                List.of("--server-url=http://192.168.0.10:8090", "--windowed", "--data-dir=/tmp/player-2"));

        assertEquals(URI.create("http://192.168.0.10:8090"), config.serverUrl());
        assertTrue(config.windowed());
        assertEquals(Path.of("/tmp/player-2"), config.dataDir());
    }
}
