package webpresenter.server.planning;

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

/** Slide appearance: the item's over the event's over the song's own, as players receive it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class SlideStyleIntegrationTests {

    static final String LYRICS = """
            {"parts":[{"id":"v","kind":"VERSE","label":"E","slides":[{"lines":["Castelo forte"]}]}],"order":["v"]}""";

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
    void itemOverEventOverSong() throws Exception {
        String songImage = image("fundo-da-musica");
        String eventImage = image("fundo-do-evento");
        String song = id(send(post("/api/songs"), tech(), """
                {"title":"Castelo Forte","lyrics":%s,"backgroundMediaId":"%s","textColor":"#ffd27a","textScale":110}"""
                .formatted(LYRICS, songImage)));
        String event = id(send(post("/api/events"), leader(), "{\"title\":\"Culto com tema\"}"));
        send(post("/api/events/" + event + "/items"), leader(), "{\"type\":\"SONG\",\"songId\":\"%s\"}".formatted(song));
        JsonNode added = body(send(post("/api/events/" + event + "/items"), leader(),
                "{\"type\":\"SLIDE\",\"slideTitle\":\"Avisos\"}"));
        String slide = added.get("items").get(1).get("id").asString();

        // The event sets only the size: songs keep their own background and colour.
        send(put("/api/events/" + event + "/style"), leader(), "{\"textScale\":130}")
                .andExpect(jsonPath("$.style.textScale").value(130));
        // The slide item sets a background and a colour.
        send(put("/api/events/" + event + "/items/" + slide + "/style"), leader(),
                "{\"backgroundMediaId\":\"%s\",\"textColor\":\"#bfe3ff\"}".formatted(eventImage))
                .andExpect(jsonPath("$.items[1].style.backgroundTitle").value("fundo-do-evento"));

        try (var player = TestPlayers.approved(port, mvc)) {
            player.expect(Message.Prepare.class);
            send(post("/api/events/" + event + "/current"), leader()).andExpect(status().isOk());
            Message.Prepare plan = player.expect(Message.Prepare.class);
            Message.SlideStyle songStyle = plan.items().get(0).style();
            assertThat(songStyle.background().mediaId()).isEqualTo(songImage);     // the song's own
            assertThat(songStyle.textColor()).isEqualTo("#ffd27a");                  // the song's own
            assertThat(songStyle.textScale()).isEqualTo(130);                        // the event's
            assertThat(plan.items().get(0).song().background().mediaId()).isEqualTo(songImage);
            Message.SlideStyle slideStyle = plan.items().get(1).style();
            assertThat(slideStyle.background().mediaId()).isEqualTo(eventImage);    // the item's
            assertThat(slideStyle.textColor()).isEqualTo("#bfe3ff");
            assertThat(slideStyle.textScale()).isEqualTo(130);

            // An event background replaces the songs' own.
            send(put("/api/events/" + event + "/style"), leader(),
                    "{\"backgroundMediaId\":\"%s\",\"textScale\":130}".formatted(eventImage));
            plan = player.expect(Message.Prepare.class);
            assertThat(plan.items().get(0).style().background().mediaId()).isEqualTo(eventImage);
            assertThat(plan.items().get(0).style().textColor()).isEqualTo("#ffd27a");
        }

        // Validation, and images in use are protected.
        send(put("/api/events/" + event + "/style"), leader(), "{\"textColor\":\"azul\"}").andExpect(status().isBadRequest());
        send(put("/api/events/" + event + "/style"), leader(), "{\"textScale\":300}").andExpect(status().isBadRequest());
        mvc.perform(delete("/api/media/" + eventImage).with(tech())).andExpect(status().isConflict());
    }

    private String image(String title) throws Exception {
        Path folder = Files.createDirectories(dir.resolve(title));
        return id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", title + ".png", "image/png",
                Files.readAllBytes(TestMedia.image(folder))))
                .param("purpose", "SONG_BACKGROUND").with(tech())));
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
