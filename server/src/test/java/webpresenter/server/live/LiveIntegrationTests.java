package webpresenter.server.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.protocol.Message;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;
import webpresenter.server.devices.TestPlayers;

/** Live control end to end: commands, what players receive, what web clients see. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class LiveIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    /** Real HTTP calls (the SSE stream) authenticate with a stubbed decoder. */
    @MockitoBean
    JwtDecoder jwtDecoder;

    @TempDir
    Path dir;

    String slide;
    String video;
    String image;

    @BeforeEach
    void prepareEvent() throws Exception {
        String videoMedia = upload(TestMedia.video(dir), "abertura.mp4", "video/mp4");
        String imageMedia = upload(TestMedia.image(dir), "versiculo.png", "image/png");
        String event = id(call("/api/events", "{\"title\":\"Culto ao vivo\"}"));
        call("/api/events/" + event + "/items", "{\"type\":\"SLIDE\",\"slideTitle\":\"Bem-vindos\"}");
        call("/api/events/" + event + "/items", "{\"type\":\"MEDIA\",\"mediaId\":\"" + videoMedia + "\"}");
        JsonNode detail = json.readTree(call("/api/events/" + event + "/items",
                "{\"type\":\"MEDIA\",\"mediaId\":\"" + imageMedia + "\"}").andReturn().getResponse().getContentAsString());
        slide = detail.at("/items/0/id").asString();
        video = detail.at("/items/1/id").asString();
        image = detail.at("/items/2/id").asString();
        call("/api/events/" + event + "/current", null).andExpect(status().isOk());
    }

    @AfterEach
    void clearCurrentEvent() throws Exception {
        mvc.perform(delete("/api/events/current").with(user("admin", "ADMIN")));
    }

    @Test
    void operatorDrivesTheEventAndPlayersFollow() throws Exception {
        try (var player = TestPlayers.approved(port, mvc)) {
            String eventId = player.expect(Message.Prepare.class).eventId();
            player.send(new Message.CacheStatus(eventId, 2, 2, 0, 100, 100));

            command("NEXT", null).andExpect(jsonPath("$.status").value("SHOWING"))
                    .andExpect(jsonPath("$.currentIndex").value(0));
            Message.Play first = player.expect(Message.Play.class);
            assertThat(first.itemId()).isEqualTo(slide);
            assertThat(first.startAt()).isAfter(Instant.now().minusSeconds(1));

            command("NEXT", null).andExpect(jsonPath("$.status").value("PLAYING"));
            assertThat(player.expect(Message.Play.class).itemId()).isEqualTo(video);
            command("PAUSE", null).andExpect(jsonPath("$.status").value("PAUSED"));
            player.expect(Message.Pause.class);
            command("RESUME", null).andExpect(jsonPath("$.status").value("PLAYING"));
            player.expect(Message.Resume.class);

            // Positions reported by players are visible to operators.
            player.send(new Message.PlaybackState(video, Message.PlaybackStatus.PLAYING, 1200, 2000, null));
            await().untilAsserted(() -> mvc.perform(get("/api/live").with(user("musico", "MUSICIAN")))
                    .andExpect(jsonPath("$.players[?(@.id == '%s')].playback", player.deviceId).value("PLAYING"))
                    .andExpect(jsonPath("$.players[?(@.id == '%s')].ready", player.deviceId).value(true)));

            // When the video ends on the player, the live state goes back to the rest screen.
            player.send(new Message.PlaybackState(video, Message.PlaybackStatus.ENDED, 2000, 2000, null));
            await().untilAsserted(() -> mvc.perform(get("/api/live").with(user("musico", "MUSICIAN")))
                    .andExpect(jsonPath("$.status").value("REST"))
                    .andExpect(jsonPath("$.currentIndex").value(1)));

            // Next continues after the item that ended; jumping and going back work.
            command("NEXT", null).andExpect(jsonPath("$.currentIndex").value(2));
            assertThat(player.expect(Message.Play.class).itemId()).isEqualTo(image);
            command("PLAY", slide).andExpect(jsonPath("$.currentIndex").value(0));
            assertThat(player.expect(Message.Play.class).itemId()).isEqualTo(slide);
            command("PREVIOUS", null).andExpect(jsonPath("$.currentIndex").value(0));
            player.expect(Message.Play.class);

            command("REST", null).andExpect(jsonPath("$.status").value("REST"));
            player.expect(Message.ShowRest.class);
            // Past the last item, NEXT goes to the rest screen.
            command("PLAY", image);
            player.expect(Message.Play.class);
            command("NEXT", null).andExpect(jsonPath("$.status").value("REST"));
            player.expect(Message.ShowRest.class);
        }
    }

    @Test
    void aPlayerConnectingDuringAVideoJoinsAtTheCurrentPosition() throws Exception {
        try (var first = TestPlayers.approved(port, mvc)) {
            command("PLAY", video);
            first.expect(Message.Play.class);
            first.send(new Message.PlaybackState(video, Message.PlaybackStatus.PLAYING, 900, 2000, null));
            Thread.sleep(300);

            try (var late = TestPlayers.approved(port, mvc)) {
                Message.Play join = late.expect(Message.Play.class);
                assertThat(join.itemId()).isEqualTo(video);
                assertThat(join.startPositionMs()).isGreaterThanOrEqualTo(1100);
            }
        }
    }

    @Test
    void webClientsReceiveEveryChangeOverServerSentEvents() throws Exception {
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("t").header("alg", "none")
                .subject("musico").claim("preferred_username", "musico")
                .claim("realm_access", Map.of("roles", List.of("musician")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/live/stream"))
                        .header("Authorization", "Bearer t").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        events.add(json.readTree(line.substring(5)));
                    }
                }
            } catch (Exception ignored) {
                // stream closed at the end of the test
            }
        });
        try {
            await().until(() -> !events.isEmpty());
            assertThat(events.getLast().get("title").asString()).isEqualTo("Culto ao vivo");

            command("PLAY", image);
            await().untilAsserted(() -> assertThat(events.getLast().get("currentIndex").asInt()).isEqualTo(2));
            assertThat(events.getLast().get("status").asString()).isEqualTo("SHOWING");
        } finally {
            response.body().close();
            reader.interrupt();
        }
    }

    @Test
    void musiciansFollowButCannotControl() throws Exception {
        mvc.perform(post("/api/live/commands").with(user("musico", "MUSICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"NEXT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/live").with(user("musico", "MUSICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void commandsNeedACurrentEvent() throws Exception {
        mvc.perform(delete("/api/events/current").with(user("admin", "ADMIN")));
        command("NEXT", null).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions command(String action, String itemId) throws Exception {
        String body = itemId == null ? "{\"action\":\"" + action + "\"}"
                : "{\"action\":\"" + action + "\",\"itemId\":\"" + itemId + "\"}";
        return mvc.perform(post("/api/live/commands").with(user("lider", "WORSHIP_LEADER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions call(String path, String body) throws Exception {
        var request = post(path).with(user("lider", "WORSHIP_LEADER"));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private String id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String upload(Path file, String name, String type) throws Exception {
        return id(mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", name, type, Files.readAllBytes(file)))
                        .with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isCreated()));
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
