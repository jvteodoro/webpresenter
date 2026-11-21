package webpresenter.server.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.protocol.Message;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;

/** Pairing, approval, configuration and removal of a player over the real WebSocket endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class DeviceFlowIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    @Test
    void pairsApprovesConfiguresAndRemovesAPlayer() throws Exception {
        UUID id = UUID.randomUUID();
        String token;

        // 1. Unknown player: pending, with a pairing code visible to operators.
        try (TestPlayer player = TestPlayer.connect(port, id).hello(null)) {
            String code = player.expect(Message.PairingPending.class).pairingCode();
            assertThat(code).matches("[2-9A-HJKMNP-Z]{3}-[2-9A-HJKMNP-Z]{3}");

            mvc.perform(get("/api/devices").with(operator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '%s')].status", id).value("PENDING"))
                    .andExpect(jsonPath("$[?(@.id == '%s')].pairingCode", id).value(code))
                    .andExpect(jsonPath("$[?(@.id == '%s')].online", id).value(true))
                    .andExpect(jsonPath("$[?(@.id == '%s')].displays.length()", id).value(2));

            // 2. Approval delivers the token and the configuration over the open connection.
            mvc.perform(post("/api/devices/{id}/approve", id).with(operator())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Telão principal\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.pairingCode").doesNotExist())
                    .andExpect(jsonPath("$.approvedBy").value("tecnico"));
            token = player.expect(Message.Paired.class).token();
            Message.Config config = player.expect(Message.Config.class);
            assertThat(config.deviceName()).isEqualTo("Telão principal");
            assertThat(config.restScreen()).isNull();
        }

        // 3. Reconnecting with the token goes straight to the configuration.
        try (TestPlayer player = TestPlayer.connect(port, id).hello(token)) {
            player.expect(Message.Config.class);

            // 4. Choosing a rest screen pushes a config with a downloadable, verifiable file.
            byte[] image = Files.readAllBytes(TestMedia.image(dir));
            String mediaId = upload(image, "descanso.png");
            mvc.perform(put("/api/devices/{id}/settings", id).with(operator())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Telão principal\",\"displayIndex\":1,\"restMediaId\":\"" + mediaId + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.restScreen.title").value("descanso"))
                    .andExpect(jsonPath("$.displayIndex").value(1));
            Message.Config withRest = player.expect(Message.Config.class);
            assertThat(withRest.displayIndex()).isEqualTo(1);
            assertThat(withRest.restScreen().kind()).isEqualTo(Message.MediaKind.IMAGE);
            assertThat(withRest.restScreen().sha256()).isEqualTo(sha256(image));
            byte[] downloaded = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create(withRest.restScreen().url())).build(),
                            HttpResponse.BodyHandlers.ofByteArray())
                    .body();
            assertThat(sha256(downloaded)).isEqualTo(sha256(image));

            // 5. Cache progress reported by the player shows up for operators.
            player.send(new Message.CacheStatus(null, 1, 1, 0, image.length, image.length));
            await().atMost(TestPlayer.timeout()).untilAsserted(() -> mvc.perform(get("/api/devices").with(operator()))
                    .andExpect(jsonPath("$[?(@.id == '%s')].cacheStatus.itemsReady", id).value(1)));

            // 6. Deleting the media clears the rest screen on the player.
            mvc.perform(delete("/api/media/{id}", mediaId).with(operator())).andExpect(status().isNoContent());
            assertThat(player.expect(Message.Config.class).restScreen()).isNull();

            // 7. Removing the device revokes it and closes the connection.
            mvc.perform(delete("/api/devices/{id}", id).with(operator())).andExpect(status().isNoContent());
            assertThat(player.expect(Message.Revoked.class).reason()).isNotBlank();
            player.awaitClose();
        }
        mvc.perform(get("/api/devices").with(operator()))
                .andExpect(jsonPath("$[?(@.id == '%s')]", id).isEmpty());
    }

    @Test
    void approvedDeviceRejectsConnectionsWithoutTheToken() throws Exception {
        UUID id = UUID.randomUUID();
        try (TestPlayer player = TestPlayer.connect(port, id).hello(null)) {
            player.expect(Message.PairingPending.class);
            mvc.perform(post("/api/devices/{id}/approve", id).with(operator())).andExpect(status().isOk());
            player.expect(Message.Paired.class);
        }

        try (TestPlayer impostor = TestPlayer.connect(port, id).hello("forged-token")) {
            assertThat(impostor.expect(Message.Error.class).code()).isEqualTo("INVALID_TOKEN");
            impostor.awaitClose();
        }
        mvc.perform(get("/api/devices").with(operator()))
                .andExpect(jsonPath("$[?(@.id == '%s')].status", id).value("APPROVED"));
    }

    @Test
    void aNewerConnectionOfTheSameDeviceReplacesTheOlderOne() {
        UUID id = UUID.randomUUID();
        try (TestPlayer first = TestPlayer.connect(port, id).hello(null)) {
            String code = first.expect(Message.PairingPending.class).pairingCode();
            try (TestPlayer second = TestPlayer.connect(port, id).hello(null)) {
                assertThat(second.expect(Message.PairingPending.class).pairingCode()).isEqualTo(code);
                assertThat(first.awaitClose()).isEqualTo(webpresenter.protocol.ProtocolVersion.CLOSE_REPLACED);
            }
        }
    }

    @Test
    void offlinePlayersCannotBeApproved() throws Exception {
        UUID id = UUID.randomUUID();
        try (TestPlayer player = TestPlayer.connect(port, id).hello(null)) {
            player.expect(Message.PairingPending.class);
        }
        await().atMost(TestPlayer.timeout()).untilAsserted(() -> mvc.perform(get("/api/devices").with(operator()))
                .andExpect(jsonPath("$[?(@.id == '%s')].online", id).value(false)));

        mvc.perform(post("/api/devices/{id}/approve", id).with(operator())).andExpect(status().isConflict());
    }

    @Test
    void rejectsPlayersSpeakingAnotherProtocolVersion() {
        try (TestPlayer player = TestPlayer.connect(port, UUID.randomUUID())) {
            player.send(new Message.Hello(player.deviceId.toString(), "x", "old", 99, null, null));
            assertThat(player.expect(Message.Error.class).code()).isEqualTo("UNSUPPORTED_PROTOCOL");
            player.awaitClose();
        }
    }

    @Test
    void requiresHelloFirst() {
        try (TestPlayer player = TestPlayer.connect(port, UUID.randomUUID())) {
            player.send(new Message.CacheStatus(null, 0, 0, 0, 0, 0));
            assertThat(player.expect(Message.Error.class).code()).isEqualTo("HELLO_REQUIRED");
            player.awaitClose();
        }
    }

    @Test
    void onlyAvTechniciansAndAdminsManageDevices() throws Exception {
        mvc.perform(get("/api/devices").with(user("musico", "MUSICIAN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/devices").with(user("lider", "WORSHIP_LEADER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
    }

    private String upload(byte[] content, String filename) throws Exception {
        String body = mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", filename, "image/png", content)).with(operator()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private static RequestPostProcessor operator() {
        return user("tecnico", "AV_TECHNICIAN");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
