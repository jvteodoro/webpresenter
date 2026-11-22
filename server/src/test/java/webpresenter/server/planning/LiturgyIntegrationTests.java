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
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;

/** Liturgy templates with fixed and variable slots, and the events made from them. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class LiturgyIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    @AfterEach
    void clearCurrent() throws Exception {
        mvc.perform(delete("/api/events/current").with(leader()));
    }

    @Test
    void templatesMakeEventsWhoseVariableSlotsAreFilledEachWeek() throws Exception {
        String video = id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "informativo.mp4",
                "video/mp4", Files.readAllBytes(TestMedia.video(dir)))).with(tech())));
        String song = id(send(post("/api/songs"), tech(), """
                {"title":"Hino da semana","lyrics":{"parts":[{"id":"v","kind":"VERSE","label":"E","slides":[{"lines":["x"]}]}],"order":["v"]}}"""));

        String template = id(send(post("/api/liturgy/templates"), leader(),
                "{\"name\":\"Sábado\",\"weekdays\":[6],\"startTime\":\"09:30\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.weekdays[0]").value("SATURDAY")));
        send(put("/api/liturgy/templates/" + template + "/slots"), leader(), """
                {"slots":[
                  {"type":"SCENE","sceneTitle":"Escola Sabatina","sceneStarts":"09:30","sceneEnds":"10:10"},
                  {"type":"SLOT","label":"Hino inicial","slotKind":"SONG"},
                  {"type":"MEDIA","mediaId":"%s"},
                  {"type":"SLIDE","slideTitle":"Oração"},
                  {"type":"SLOT","label":"Momento da lição","slotKind":"ANY"},
                  {"type":"TIMER","label":"Escola Sabatina","timer":{"mode":"SCENE_END"}}
                ]}""".formatted(video))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[1].title").value("Hino inicial"))
                .andExpect(jsonPath("$.slots[1].detail").value("A escolher a cada evento"))
                .andExpect(jsonPath("$.slots[2].title").value("informativo"));
        mvc.perform(get("/api/liturgy/templates").with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$[?(@.id == '%s')].variableSlots", template).value(2));

        // Slots are validated like event items.
        send(put("/api/liturgy/templates/" + template + "/slots"), leader(),
                "{\"slots\":[{\"type\":\"SONG\",\"songId\":\"%s\"}]}".formatted(java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        send(put("/api/liturgy/templates/" + template + "/slots"), leader(), "{\"slots\":[{\"type\":\"SLOT\",\"label\":\"X\"}]}")
                .andExpect(status().isBadRequest());

        // The event of a Saturday: fixed slots copied, variable ones to fill; one per date.
        JsonNode created = body(send(post("/api/liturgy/templates/" + template + "/events"), leader(),
                "{\"date\":\"2026-11-28\",\"startsAt\":\"2026-11-28T12:30:00Z\"}").andExpect(status().isCreated()));
        assertThat(created.get("title").asString()).isEqualTo("Sábado · 28/11");
        assertThat(created.get("pendingSlots").asInt()).isEqualTo(2);
        String event = created.get("id").asString();
        send(post("/api/liturgy/templates/" + template + "/events"), leader(), "{\"date\":\"2026-11-28\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.eventId").value(event));

        JsonNode detail = body(mvc.perform(get("/api/events/" + event).with(leader())));
        assertThat(detail.get("templateDate").asString()).isEqualTo("2026-11-28");
        assertThat(detail.get("items").valueStream().map(i -> i.get("type").asString()).toList())
                .containsExactly("SCENE", "SLOT", "MEDIA", "SLIDE", "SLOT", "TIMER");
        String hymnSlot = detail.get("items").get(1).get("id").asString();
        String lessonSlot = detail.get("items").get(4).get("id").asString();
        assertThat(detail.get("items").get(1).get("slotKind").asString()).isEqualTo("SONG");

        // Filling: the slot expects a song; "any" takes a slide too.
        send(put("/api/events/" + event + "/items/" + hymnSlot + "/fill"), leader(),
                "{\"type\":\"MEDIA\",\"mediaId\":\"%s\"}".formatted(video)).andExpect(status().isBadRequest());
        send(put("/api/events/" + event + "/items/" + hymnSlot + "/fill"), leader(),
                "{\"type\":\"SONG\",\"songId\":\"%s\"}".formatted(song))
                .andExpect(jsonPath("$.items[1].type").value("SONG"))
                .andExpect(jsonPath("$.items[1].title").value("Hino da semana"))
                .andExpect(jsonPath("$.pendingSlots").value(1));

        // Unfilled slots are not presented.
        send(post("/api/events/" + event + "/current"), leader()).andExpect(status().isOk());
        mvc.perform(get("/api/live").with(leader()))
                .andExpect(jsonPath("$.items.length()").value(4))   // song, media, slide, timer
                .andExpect(jsonPath("$.items[3].timer.targetTime").value("10:10"));
        send(put("/api/events/" + event + "/items/" + lessonSlot + "/fill"), leader(),
                "{\"type\":\"SLIDE\",\"slideTitle\":\"Lição 9\"}").andExpect(jsonPath("$.pendingSlots").value(0));
        mvc.perform(get("/api/events").with(leader()))
                .andExpect(jsonPath("$[?(@.id == '%s')].templateId", event).value(template));

        // Library media used by a template cannot be deleted; deleting the template keeps its events.
        mvc.perform(delete("/api/events/current").with(leader()));
        mvc.perform(delete("/api/events/" + event).with(leader())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/" + video).with(tech()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.usages[0]").value("modelo de liturgia \"Sábado\""));
        send(post("/api/liturgy/templates"), user("musico", "MUSICIAN"), "{\"name\":\"x\"}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/liturgy/templates/" + template).with(leader())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/" + video).with(tech())).andExpect(status().isNoContent());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user) throws Exception {
        return mvc.perform(request.with(user));
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
