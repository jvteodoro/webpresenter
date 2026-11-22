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

/** Clock and timer items as players and the live page receive them; the clock on the rest screen. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class ClockTimerIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @LocalServerPort
    int port;

    @TempDir
    Path dir;

    @AfterEach
    void clearCurrent() throws Exception {
        mvc.perform(delete("/api/events/current").with(leader()));
    }

    @Test
    void timersCountToTheirSceneEndATimeOrADurationWithSignals() throws Exception {
        String bell = id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "sino.m4a", "audio/mp4",
                Files.readAllBytes(TestMedia.tone(dir, 1500 + (int) (System.nanoTime() % 300))))).param("purpose", "SOUND_EFFECT")
                .with(tech())));
        String event = id(send(post("/api/events"), "{\"title\":\"Sábado com cronômetro\"}"));
        send(post("/api/events/" + event + "/items"),
                "{\"type\":\"SCENE\",\"sceneTitle\":\"Escola Sabatina\",\"sceneStarts\":\"09:30\",\"sceneEnds\":\"10:10\"}");
        send(post("/api/events/" + event + "/items"), "{\"type\":\"CLOCK\",\"label\":\"Bom dia\"}");
        JsonNode detail = body(send(post("/api/events/" + event + "/items"), """
                {"type":"TIMER","label":"Escola Sabatina","timer":{"mode":"SCENE_END",
                 "signals":[{"beforeEndSeconds":300,"soundMediaId":"%s"},{"beforeEndSeconds":0,"soundMediaId":"%s"}]}}"""
                .formatted(bell, bell)));
        assertThat(detail.get("items").get(2).get("timer").get("signals").get(0).get("sound").asString()).isEqualTo("sino");
        String duration = body(send(post("/api/events/" + event + "/items"),
                "{\"type\":\"TIMER\",\"label\":\"Oração\",\"timer\":{\"mode\":\"DURATION\",\"durationSeconds\":120}}"))
                .get("items").get(3).get("id").asString();
        send(post("/api/events/" + event + "/items"), "{\"type\":\"TIMER\",\"timer\":{\"mode\":\"TIME\",\"time\":\"11:45\"}}");

        try (var player = TestPlayers.approved(port, mvc)) {
            player.expect(Message.Prepare.class);
            mvc.perform(post("/api/events/" + event + "/current").with(leader())).andExpect(status().isOk());
            Message.Prepare plan = player.expect(Message.Prepare.class);
            assertThat(plan.items()).extracting(Message.PlanItem::type)
                    .containsExactly(Message.ItemType.CLOCK, Message.ItemType.TIMER, Message.ItemType.TIMER, Message.ItemType.TIMER);
            assertThat(plan.items().get(0).title()).isEqualTo("Bom dia");
            Message.TimerContent sceneEnd = plan.items().get(1).timer();
            assertThat(sceneEnd.targetTime()).isEqualTo("10:10");                // the scene's end
            assertThat(sceneEnd.signals()).extracting(Message.TimerSignal::beforeEndMs).containsExactly(300_000L, 0L);
            assertThat(sceneEnd.signals().getFirst().sound().mediaId()).isEqualTo(bell);
            assertThat(plan.items().get(2).timer().durationMs()).isEqualTo(120_000L);
            assertThat(plan.items().get(3).timer().targetTime()).isEqualTo("11:45");

            // The live page gets the same targets (computed in local time by the browser).
            mvc.perform(get("/api/live").with(leader()))
                    .andExpect(jsonPath("$.items[1].timer.targetTime").value("10:10"))
                    .andExpect(jsonPath("$.items[1].timer.signalsBeforeEndMs[0]").value(300000))
                    .andExpect(jsonPath("$.items[2].timer.durationMs").value(120000));

            // Editing a timer reaches the players.
            send(put("/api/events/" + event + "/items/" + duration + "/clock"),
                    "{\"label\":\"Oração final\",\"timer\":{\"mode\":\"DURATION\",\"durationSeconds\":180}}")
                    .andExpect(jsonPath("$.items[3].title").value("Oração final"));
            assertThat(player.expect(Message.Prepare.class).items().get(2).timer().durationMs()).isEqualTo(180_000L);

            // Clock on the rest screen.
            mvc.perform(put("/api/devices/" + player.deviceId + "/settings").with(tech()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Telão\",\"displayIndex\":0,\"restClock\":true}"))
                    .andExpect(jsonPath("$.restClock").value(true));
            assertThat(player.expect(Message.Config.class).restClock()).isTrue();
        }

        // A sound used by a timer cannot be deleted.
        mvc.perform(delete("/api/media/" + bell).with(tech()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.usages[0]").value("evento \"Sábado com cronômetro\""));
    }

    @Test
    void validatesTimers() throws Exception {
        String event = id(send(post("/api/events"), "{\"title\":\"Validação de cronômetro\"}"));
        String image = id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "quadro.png", "image/png",
                Files.readAllBytes(TestMedia.image(dir)))).with(tech())));
        send(post("/api/events/" + event + "/items"), "{\"type\":\"TIMER\"}").andExpect(status().isBadRequest());
        send(post("/api/events/" + event + "/items"), "{\"type\":\"TIMER\",\"timer\":{\"mode\":\"TIME\"}}")
                .andExpect(status().isBadRequest());
        send(post("/api/events/" + event + "/items"), "{\"type\":\"TIMER\",\"timer\":{\"mode\":\"DURATION\",\"durationSeconds\":0}}")
                .andExpect(status().isBadRequest());
        send(post("/api/events/" + event + "/items"), """
                {"type":"TIMER","timer":{"mode":"SCENE_END","signals":[{"beforeEndSeconds":60,"soundMediaId":"%s"}]}}"""
                .formatted(image)).andExpect(status().isBadRequest());
        // Without a scene end it only counts up.
        send(post("/api/events/" + event + "/items"), "{\"type\":\"TIMER\",\"timer\":{\"mode\":\"SCENE_END\"}}")
                .andExpect(jsonPath("$.items[0].title").value("Cronômetro"));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(leader()).contentType(MediaType.APPLICATION_JSON).content(body));
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
