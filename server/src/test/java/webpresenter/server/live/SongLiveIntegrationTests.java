package webpresenter.server.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.protocol.Message;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;
import webpresenter.server.devices.TestPlayers;

/** Songs in events: what players receive and how slides are driven live. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class SongLiveIntegrationTests {

    static final String LYRICS = """
            {"parts":[
              {"id":"v1","kind":"VERSE","label":"Estrofe 1","slides":[{"lines":["Castelo forte é nosso Deus"]},{"lines":["Refúgio e fortaleza"]}]},
              {"id":"c","kind":"CHORUS","label":"Coro","slides":[{"lines":["Com força nos protege"],"cue":"Todos:"}]}],
             "order":["v1","c"]}""";

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    String timedItem;
    String lyricsOnlyItem;
    String slideItem;

    @BeforeEach
    void prepareEvent() throws Exception {
        String audio = id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "castelo.m4a", "audio/mp4",
                Files.readAllBytes(TestMedia.tone(dir, 600 + (int) (System.nanoTime() % 3000))))).param("purpose", "SONG_AUDIO")
                .with(tech())));
        JsonNode song = body(call("/api/songs", tech(), """
                {"title":"Castelo Forte","status":"READY","lyrics":%s}""".formatted(LYRICS)));
        String songId = song.get("id").asString();
        String recording = body(call("/api/songs/" + songId + "/recordings", tech(), """
                {"kind":"SUNG","mediaId":"%s","timing":{"cues":[{"slide":0,"startMs":0},{"slide":1,"startMs":700},{"slide":2,"startMs":1400}]}}
                """.formatted(audio))).at("/recordings/0/id").asString();

        String event = id(call("/api/events", leader(), "{\"title\":\"Culto com música\"}"));
        call("/api/events/" + event + "/items", leader(),
                "{\"type\":\"SONG\",\"songId\":\"" + songId + "\",\"recordingId\":\"" + recording + "\"}")
                .andExpect(jsonPath("$.items[0].song.timed").value(true))
                .andExpect(jsonPath("$.items[0].song.recordingKind").value("SUNG"));
        call("/api/events/" + event + "/items", leader(), "{\"type\":\"SONG\",\"songId\":\"" + songId + "\"}")
                .andExpect(jsonPath("$.items[1].song.recordingId").doesNotExist());
        JsonNode detail = body(call("/api/events/" + event + "/items", leader(), "{\"type\":\"SLIDE\",\"slideTitle\":\"Avisos\"}"));
        timedItem = detail.at("/items/0/id").asString();
        lyricsOnlyItem = detail.at("/items/1/id").asString();
        slideItem = detail.at("/items/2/id").asString();
        call("/api/events/" + event + "/items", leader(),
                "{\"type\":\"SONG\",\"songId\":\"" + songId + "\",\"recordingId\":\"" + java.util.UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest());
        call("/api/events/" + event + "/current", leader(), null).andExpect(status().isOk());
    }

    @AfterEach
    void clear() throws Exception {
        mvc.perform(delete("/api/events/current").with(leader()));
    }

    @Test
    void playersReceiveSongsAndOperatorsDriveSlides() throws Exception {
        try (var player = TestPlayers.approved(port, mvc)) {
            Message.Prepare plan = player.expect(Message.Prepare.class);
            Message.SongContent timed = plan.items().get(0).song();
            assertThat(plan.items().get(0).type()).isEqualTo(Message.ItemType.SONG);
            assertThat(timed.slides()).extracting(Message.SongSlide::label).containsExactly("Estrofe 1", "Estrofe 1", "Coro");
            assertThat(timed.slides().get(2).cue()).isEqualTo("Todos:");
            assertThat(timed.audio().kind()).isEqualTo(Message.MediaKind.AUDIO);
            assertThat(timed.cues()).containsExactly(0L, 700L, 1400L);
            assertThat(plan.items().get(1).song().audio()).isNull();
            assertThat(plan.items().get(1).song().cues()).isEmpty();

            // Next starts a timed song from the beginning: intro (no slide yet), audio from 0.
            command("NEXT", null, null).andExpect(jsonPath("$.currentSlide").value(-1)).andExpect(jsonPath("$.status").value("PLAYING"));
            Message.Play play = player.expect(Message.Play.class);
            assertThat(play.itemId()).isEqualTo(timedItem);
            assertThat(play.slide()).isNull();
            assertThat(play.startPositionMs()).isZero();
            // Next moves slide by slide (from the intro to slide 0, then 1).
            command("NEXT", null, null).andExpect(jsonPath("$.currentSlide").value(0));
            assertThat(player.expect(Message.ShowSlide.class).slide()).isZero();
            command("NEXT", null, null).andExpect(jsonPath("$.currentSlide").value(1));
            assertThat(player.expect(Message.ShowSlide.class)).isEqualTo(new Message.ShowSlide(timedItem, 1));

            // Timed songs advance on the player; the live state follows its reports.
            player.send(new Message.PlaybackState(timedItem, Message.PlaybackStatus.PLAYING, 1500, 2000, null, 2));
            await().untilAsserted(() -> mvc.perform(get("/api/live").with(user("musico", "MUSICIAN")))
                    .andExpect(jsonPath("$.currentSlide").value(2))
                    .andExpect(jsonPath("$.items[0].slides.length()").value(3))
                    .andExpect(jsonPath("$.items[0].timed").value(true)));

            // After the last slide, next goes to the next item (the lyrics-only song).
            command("NEXT", null, null).andExpect(jsonPath("$.currentIndex").value(1))
                    .andExpect(jsonPath("$.currentSlide").value(0)).andExpect(jsonPath("$.status").value("SHOWING"));
            assertThat(player.expect(Message.Play.class).itemId()).isEqualTo(lyricsOnlyItem);

            // Jump to a slide; previous at slide 0 goes back to the previous item.
            command("SLIDE", lyricsOnlyItem, 2).andExpect(jsonPath("$.currentSlide").value(2));
            assertThat(player.expect(Message.ShowSlide.class).slide()).isEqualTo(2);
            command("SLIDE", lyricsOnlyItem, 0);
            player.expect(Message.ShowSlide.class);
            // Back to the timed song: it restarts from the beginning (intro).
            command("PREVIOUS", null, null).andExpect(jsonPath("$.currentIndex").value(0)).andExpect(jsonPath("$.currentSlide").value(-1));
            player.expect(Message.Play.class);

            // Jumping into a slide of a song that is not on screen starts it there, at that slide's cue.
            command("PLAY", slideItem, null);
            player.expect(Message.Play.class);
            command("SLIDE", timedItem, 2).andExpect(jsonPath("$.currentIndex").value(0)).andExpect(jsonPath("$.currentSlide").value(2));
            Message.Play intoSong = player.expect(Message.Play.class);
            assertThat(intoSong.slide()).isEqualTo(2);
            assertThat(intoSong.startPositionMs()).isEqualTo(1400);

            command("SLIDE", slideItem, 0).andExpect(status().isConflict());
            command("SLIDE", timedItem, 9).andExpect(status().isConflict());
        }
    }

    @Test
    void aLatePlayerJoinsTheSongAtItsSlide() throws Exception {
        try (var first = TestPlayers.approved(port, mvc)) {
            command("SLIDE", lyricsOnlyItem, 1);
            first.expect(Message.Play.class);
            try (var late = TestPlayers.approved(port, mvc)) {
                Message.Play join = late.expect(Message.Play.class);
                assertThat(join.itemId()).isEqualTo(lyricsOnlyItem);
                assertThat(join.slide()).isEqualTo(1);
            }
        }
    }

    private ResultActions command(String action, String itemId, Integer slide) throws Exception {
        String body = "{\"action\":\"" + action + "\"" + (itemId == null ? "" : ",\"itemId\":\"" + itemId + "\"")
                + (slide == null ? "" : ",\"slide\":" + slide) + "}";
        return mvc.perform(post("/api/live/commands").with(leader()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions call(String path, RequestPostProcessor user, String body) throws Exception {
        var request = post(path).with(user);
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

    private static RequestPostProcessor leader() {
        return user("lider", "WORSHIP_LEADER");
    }

    private static RequestPostProcessor tech() {
        return user("tecnico", "AV_TECHNICIAN");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
