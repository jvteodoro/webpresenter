package webpresenter.server.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
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

/** Event planning through the API, and how the current event reaches connected players. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class PlanningIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    @AfterEach
    void clearCurrentEvent() throws Exception {
        mvc.perform(delete("/api/events/current").with(user("admin", "ADMIN")));
    }

    @Test
    void leaderBuildsAnEventWithMediaAndSlides() throws Exception {
        String video = upload(TestMedia.video(dir), "abertura.mp4", "video/mp4");
        String event = id(call(post("/api/events"), leader(), "{\"title\":\"Culto de domingo\",\"startsAt\":\"2026-11-22T18:00:00Z\"}")
                .andExpect(status().isCreated()));

        call(post("/api/events/{id}/items", event), leader(), "{\"type\":\"MEDIA\",\"mediaId\":\"" + video + "\"}")
                .andExpect(jsonPath("$.items[0].title").value("abertura"))
                .andExpect(jsonPath("$.items[0].media.kind").value("VIDEO"))
                .andExpect(jsonPath("$.items[0].media.durationMs").isNumber());
        JsonNode detail = body(call(post("/api/events/{id}/items", event), leader(),
                "{\"type\":\"SLIDE\",\"slideTitle\":\"Avisos\",\"slideBody\":\"Culto de oração\\nquarta, 19h30\",\"position\":0}"));
        assertThat(detail.at("/items/0/type").asString()).isEqualTo("SLIDE");
        assertThat(detail.at("/items/1/type").asString()).isEqualTo("MEDIA");
        String slide = detail.at("/items/0/id").asString();
        String media = detail.at("/items/1/id").asString();

        call(put("/api/events/{id}/items/order", event), leader(), "{\"itemIds\":[\"" + media + "\",\"" + slide + "\"]}")
                .andExpect(jsonPath("$.items[0].id").value(media))
                .andExpect(jsonPath("$.items[1].id").value(slide));
        call(put("/api/events/{id}/items/{item}", event, slide), leader(), "{\"slideTitle\":\"Avisos da semana\",\"slideBody\":\"Retiro\"}")
                .andExpect(jsonPath("$.items[1].slide.title").value("Avisos da semana"))
                .andExpect(jsonPath("$.items[1].title").value("Avisos da semana"));
        call(put("/api/events/{id}/items/order", event), leader(), "{\"itemIds\":[\"" + media + "\"]}")
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/events").with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$[?(@.id == '%s')].itemCount", event).value(2));
        mvc.perform(get("/api/events/{id}", event).with(user("musico", "MUSICIAN")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/events/{id}/items/{item}", event, slide).with(leader()))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void mediaUsedInAnEventCannotBeDeleted() throws Exception {
        String image = upload(TestMedia.image(dir), "fundo.png", "image/png");
        String event = id(call(post("/api/events"), leader(), "{\"title\":\"Santa ceia\"}"));
        call(post("/api/events/{id}/items", event), leader(), "{\"type\":\"MEDIA\",\"mediaId\":\"" + image + "\"}");

        mvc.perform(delete("/api/media/{id}", image).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.usages[0]").value("evento \"Santa ceia\""));

        mvc.perform(delete("/api/events/{id}", event).with(leader())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/{id}", image).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void theCurrentEventIsSentToPlayersAndKeptUpToDate() throws Exception {
        String image = upload(TestMedia.image(dir), "louvor.png", "image/png");
        String first = id(call(post("/api/events"), leader(), "{\"title\":\"Culto da manhã\"}"));
        String second = id(call(post("/api/events"), leader(), "{\"title\":\"Culto da noite\"}"));
        call(post("/api/events/{id}/items", first), leader(), "{\"type\":\"MEDIA\",\"mediaId\":\"" + image + "\"}");
        call(post("/api/events/{id}/items", first), leader(), "{\"type\":\"SLIDE\",\"slideTitle\":\"Bem-vindos\"}");

        try (var player = TestPlayers.approved(port, mvc)) {
            assertThat(player.expect(Message.Prepare.class).eventId()).isNull();

            // Choosing the current event sends the full plan, media with verifiable refs.
            call(post("/api/events/{id}/current", first), leader(), null).andExpect(jsonPath("$.current").value(true));
            Message.Prepare plan = player.expect(Message.Prepare.class);
            assertThat(plan.eventId()).isEqualTo(first);
            assertThat(plan.title()).isEqualTo("Culto da manhã");
            assertThat(plan.items()).extracting(Message.PlanItem::type)
                    .containsExactly(Message.ItemType.MEDIA, Message.ItemType.SLIDE);
            assertThat(plan.items().get(0).media().sha256()).hasSize(64);
            assertThat(plan.items().get(1).slide().title()).isEqualTo("Bem-vindos");

            // Editing the current event resends it.
            call(post("/api/events/{id}/items", first), leader(), "{\"type\":\"SLIDE\",\"slideTitle\":\"Ofertas\"}");
            assertThat(player.expect(Message.Prepare.class).items()).hasSize(3);

            // Readiness reported by the player is visible to the leader.
            player.send(new Message.CacheStatus(first, 1, 1, 0, 100, 100));
            org.awaitility.Awaitility.await().untilAsserted(() -> mvc.perform(get("/api/devices/readiness").with(leader()))
                    .andExpect(jsonPath("$[?(@.id == '%s')].cacheStatus.eventId", player.deviceId).value(first)));

            // Only one event is current.
            call(post("/api/events/{id}/current", second), leader(), null);
            assertThat(player.expect(Message.Prepare.class).eventId()).isEqualTo(second);
            mvc.perform(get("/api/events/{id}", first).with(leader())).andExpect(jsonPath("$.current").value(false));

            // Editing a non-current event does not disturb players; deleting the current one clears the plan.
            call(post("/api/events/{id}/items", first), leader(), "{\"type\":\"SLIDE\",\"slideTitle\":\"Extra\"}");
            mvc.perform(delete("/api/events/{id}", second).with(leader())).andExpect(status().isNoContent());
            assertThat(player.expect(Message.Prepare.class).eventId()).isNull();
        }
    }

    @Test
    void concurrentEditsOfTheSameEventKeepAConsistentOrder() throws Exception {
        String event = id(call(post("/api/events"), leader(), "{\"title\":\"Concorrência\"}"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 16; i++) {
            String body = "{\"type\":\"SLIDE\",\"slideTitle\":\"Slide " + i + "\"}";
            results.add(pool.submit(() -> call(post("/api/events/{id}/items", event), leader(), body)
                    .andReturn().getResponse().getStatus()));
        }
        int created = 0;
        for (var r : results) {
            int code = r.get();
            assertThat(code).isIn(200, 409);
            created += code == 200 ? 1 : 0;
        }
        pool.shutdown();

        JsonNode detail = body(mvc.perform(get("/api/events/{id}", event).with(leader())));
        assertThat(detail.get("items").size()).isEqualTo(created);
    }

    @Test
    void musiciansCannotEditEvents() throws Exception {
        call(post("/api/events"), user("musico", "MUSICIAN"), "{\"title\":\"x\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/devices/readiness").with(user("musico", "MUSICIAN"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        request.with(user);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String id(ResultActions result) throws Exception {
        return body(result).get("id").asString();
    }

    private String upload(Path file, String name, String type) throws Exception {
        return id(mvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", name, type, Files.readAllBytes(file)))
                        .with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().is2xxSuccessful()));
    }

    private static RequestPostProcessor leader() {
        return user("lider", "WORSHIP_LEADER");
    }

    static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

}
