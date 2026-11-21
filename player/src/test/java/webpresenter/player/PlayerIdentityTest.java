package webpresenter.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerIdentityTest {

    @TempDir
    Path dir;

    @Test
    void keepsTheSameDeviceIdAcrossRestarts() {
        var first = PlayerIdentity.load(dir).deviceId();

        assertEquals(first, PlayerIdentity.load(dir).deviceId());
    }

    @Test
    void persistsAndClearsTheToken() {
        PlayerIdentity.load(dir).storeToken("secret");
        assertEquals(Optional.of("secret"), PlayerIdentity.load(dir).token());

        PlayerIdentity.load(dir).clearToken();
        assertTrue(PlayerIdentity.load(dir).token().isEmpty());
    }
}
