package webpresenter.server.devices;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import webpresenter.protocol.Message;

/** Helpers for tests in other modules that need a connected, approved player. */
public final class TestPlayers {

    private TestPlayers() {}

    /** Connects a new player and approves it; the returned player has already received PAIRED and CONFIG. */
    public static Connected approved(int port, MockMvc mvc) throws Exception {
        TestPlayer player = TestPlayer.connect(port, UUID.randomUUID()).hello(null);
        player.expect(Message.PairingPending.class);
        mvc.perform(post("/api/devices/{id}/approve", player.deviceId)
                        .with(jwt().jwt(j -> j.subject("tecnico").claim("preferred_username", "tecnico"))
                                .authorities(new SimpleGrantedAuthority("ROLE_AV_TECHNICIAN"))))
                .andExpect(status().isOk());
        player.expect(Message.Paired.class);
        player.expect(Message.Config.class);
        return new Connected(player);
    }

    /** Public view of a test player for other packages. */
    public static final class Connected implements AutoCloseable {
        private final TestPlayer player;
        public final UUID deviceId;

        private Connected(TestPlayer player) {
            this.player = player;
            this.deviceId = player.deviceId;
        }

        public <T extends Message> T expect(Class<T> type) {
            return player.expect(type);
        }

        public void send(Message message) {
            player.send(message);
        }

        @Override
        public void close() {
            player.close();
        }
    }
}
