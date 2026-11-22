package webpresenter.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.protocol.Message;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;
import webpresenter.server.devices.TestPlayers;

/** Scenes, the background music cascade (item → scene → event → platform) and the mixer, as players receive them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class BackgroundMusicIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @LocalServerPort
    int port;

    @TempDir
    Path dir;

    @AfterEach
    void resetSettings() throws Exception {
        send(put("/api/settings/audio"), tech(), """
                {"master":100,"media":100,"background":60,"effects":100,"fadeInMs":2000,"fadeOutMs":1500,"duckPercent":0}""");
        mvc.perform(delete("/api/events/current").with(tech()));
    }

    @Test
    void playersGetTheEffectiveBackgroundOfEachItemAndTheMixer() throws Exception {
        String platformTrack = upload(1100, "plataforma");
        String eventTrack = upload(1200, "evento");
        String playlistTrack = upload(1300, "playlist");
        String playlist = id(send(post("/api/playlists"), tech(), "{\"name\":\"Escola\",\"shuffle\":true}"));
        send(put("/api/playlists/" + playlist + "/items"), tech(),
                "{\"items\":[{\"type\":\"MEDIA\",\"mediaId\":\"%s\"}]}".formatted(playlistTrack));

        // Platform default and mixer.
        send(put("/api/settings/audio"), tech(), """
                {"master":90,"media":100,"background":50,"effects":80,"fadeInMs":3000,"fadeOutMs":1000,"duckPercent":20,
                 "defaultBackground":{"kind":"MEDIA","ref":"%s"}}""".formatted(platformTrack))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultBackgroundName").value("plataforma"));

        // Event: B as its background; scene "Escola Sabatina" with the playlist; an item with silence.
        String event = id(send(post("/api/events"), leader(), "{\"title\":\"Sábado\"}"));
        String before = itemId(send(post("/api/events/" + event + "/items"), leader(),
                "{\"type\":\"SLIDE\",\"slideTitle\":\"Boas-vindas\"}"), 0);
        JsonNode withScene = body(send(post("/api/events/" + event + "/items"), leader(), """
                {"type":"SCENE","sceneTitle":"Escola Sabatina","sceneStarts":"09:30","sceneEnds":"10:10"}"""));
        String scene = withScene.get("items").get(1).get("id").asString();
        String inScene = itemId(send(post("/api/events/" + event + "/items"), leader(),
                "{\"type\":\"SLIDE\",\"slideTitle\":\"Hino\"}"), 2);
        String silent = itemId(send(post("/api/events/" + event + "/items"), leader(),
                "{\"type\":\"SLIDE\",\"slideTitle\":\"Oração\"}"), 3);
        send(put("/api/events/" + event + "/items/" + scene + "/background"), leader(),
                "{\"source\":{\"kind\":\"PLAYLIST\",\"ref\":\"%s\"}}".formatted(playlist))
                .andExpect(jsonPath("$.items[1].background.name").value("Playlist Escola"))
                .andExpect(jsonPath("$.items[1].scene.starts").value("09:30:00"));
        send(put("/api/events/" + event + "/items/" + silent + "/background"), leader(), "{\"source\":{\"kind\":\"SILENCE\"}}");
        send(put("/api/events/" + event + "/background"), leader(),
                "{\"source\":{\"kind\":\"MEDIA\",\"ref\":\"%s\"}}".formatted(eventTrack))
                .andExpect(jsonPath("$.background.name").value("evento"));

        try (var player = TestPlayers.approved(port, mvc)) {
            Message.Mixer mixer = player.expect(Message.Mixer.class);
            assertThat(mixer).isEqualTo(new Message.Mixer(90, 100, 50, 80, 3000, 1000, 20));
            player.expect(Message.Prepare.class);

            send(post("/api/events/" + event + "/current"), leader()).andExpect(status().isOk());
            Message.Prepare plan = player.expect(Message.Prepare.class);
            // Scene markers are not presented.
            assertThat(plan.items()).extracting(Message.PlanItem::itemId).containsExactly(before, inScene, silent);
            assertThat(plan.items().get(0).background().key()).isEqualTo("media:" + eventTrack);   // event
            assertThat(plan.items().get(1).background().key()).startsWith("playlist:" + playlist);  // scene
            assertThat(plan.items().get(1).background().shuffle()).isTrue();
            assertThat(plan.items().get(1).background().tracks()).extracting(Message.MediaRef::mediaId)
                    .containsExactly(playlistTrack);
            assertThat(plan.items().get(2).background()).isNull();                                   // silence
            assertThat(plan.rest().key()).isEqualTo("media:" + eventTrack);

            // Without its own background the event inherits the platform's.
            send(put("/api/events/" + event + "/background"), leader(), "{\"source\":null}");
            plan = player.expect(Message.Prepare.class);
            assertThat(plan.items().getFirst().background().key()).isEqualTo("media:" + platformTrack);
            assertThat(plan.rest().key()).isEqualTo("media:" + platformTrack);

            // Live faders reach the players at once (worship leaders may move them).
            send(put("/api/settings/audio/levels"), leader(), "{\"master\":70,\"media\":90,\"background\":40,\"effects\":80}")
                    .andExpect(status().isOk());
            assertThat(player.expect(Message.Mixer.class)).isEqualTo(new Message.Mixer(70, 90, 40, 80, 3000, 1000, 20));

            // Changing the playlist sends the new tracks.
            String another = upload(1400, "outra");
            send(put("/api/playlists/" + playlist + "/items"), tech(), """
                    {"items":[{"type":"MEDIA","mediaId":"%s"},{"type":"MEDIA","mediaId":"%s"}]}""".formatted(playlistTrack, another));
            plan = player.expect(Message.Prepare.class);
            assertThat(plan.items().get(1).background().tracks()).hasSize(2);
        }

        // Tracks in use cannot be deleted.
        mvc.perform(delete("/api/media/" + eventTrack).with(tech())).andExpect(status().isNoContent());
        send(put("/api/events/" + event + "/items/" + before + "/background"), leader(),
                "{\"source\":{\"kind\":\"MEDIA\",\"ref\":\"%s\"}}".formatted(platformTrack));
        mvc.perform(delete("/api/media/" + platformTrack).with(tech())).andExpect(status().isConflict());
    }

    @Test
    void validatesAndRestrictsSettings() throws Exception {
        send(put("/api/settings/audio"), tech(), """
                {"master":150,"media":100,"background":60,"effects":100,"fadeInMs":2000,"fadeOutMs":1500,"duckPercent":0}""")
                .andExpect(status().isBadRequest());
        send(put("/api/settings/audio"), tech(), """
                {"master":100,"media":100,"background":60,"effects":100,"fadeInMs":2000,"fadeOutMs":1500,"duckPercent":0,
                 "defaultBackground":{"kind":"PLAYLIST","ref":"%s"}}""".formatted(java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        send(put("/api/settings/audio"), leader(), """
                {"master":100,"media":100,"background":60,"effects":100,"fadeInMs":2000,"fadeOutMs":1500,"duckPercent":0}""")
                .andExpect(status().isForbidden());
        send(put("/api/settings/audio/levels"), user("musico", "MUSICIAN"), "{\"master\":1,\"media\":1,\"background\":1,\"effects\":1}")
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/settings/audio")
                .with(user("musico", "MUSICIAN"))).andExpect(jsonPath("$.defaultBackgroundName").value("Silêncio"));

        String event = id(send(post("/api/events"), leader(), "{\"title\":\"Validação\"}"));
        send(post("/api/events/" + event + "/items"), leader(), "{\"type\":\"SCENE\"}").andExpect(status().isBadRequest());
        send(post("/api/events/" + event + "/items"), leader(),
                "{\"type\":\"SCENE\",\"sceneTitle\":\"X\",\"sceneStarts\":\"10:00\",\"sceneEnds\":\"09:00\"}")
                .andExpect(status().isBadRequest());
        send(put("/api/events/" + event + "/background"), leader(),
                "{\"source\":{\"kind\":\"MEDIA\",\"ref\":\"%s\"}}".formatted(java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    private String upload(int frequency, String title) throws Exception {
        Path file = TestMedia.tone(dir, frequency + (int) (System.nanoTime() % 90));
        return id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", title + ".m4a", "audio/mp4",
                Files.readAllBytes(file))).param("purpose", "BACKGROUND_MUSIC").with(tech())));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user) throws Exception {
        return mvc.perform(request.with(user));
    }

    private String itemId(ResultActions result, int index) throws Exception {
        return body(result).get("items").get(index).get("id").asString();
    }

    private String id(ResultActions result) throws Exception {
        return body(result).get("id").asString();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static RequestPostProcessor tech() {
        return user("tecnico", "AV_TECHNICIAN");
    }

    private static RequestPostProcessor leader() {
        return user("lider", "WORSHIP_LEADER");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
