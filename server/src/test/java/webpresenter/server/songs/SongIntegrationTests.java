package webpresenter.server.songs;

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
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class SongIntegrationTests {

    static final String LYRICS = """
            {"parts":[
              {"id":"v1","kind":"VERSE","label":"Estrofe 1","slides":[{"lines":["Castelo forte é nosso Deus","Refúgio e fortaleza"]}]},
              {"id":"c","kind":"CHORUS","label":"Coro","slides":[{"lines":["Coro linha 1"],"cue":"Todos:"},{"lines":["Coro linha 2"]}]}],
             "order":["v1","c","c"]}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    @Test
    void createsSearchesAndTimesASong() throws Exception {
        String song = id(send(post("/api/songs"), tech(), """
                {"title":"Castelo Forte","language":"pt","status":"READY","lyrics":%s,
                 "collections":[{"slug":"hinario-teste","name":"Hinário Teste","group":"Hinários","number":"33"}]}
                """.formatted(LYRICS)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.slideSequence.length()").value(5))
                .andExpect(jsonPath("$.slideSequence[1].cue").value("Todos:"))
                .andExpect(jsonPath("$.collections[0].number").value("33")));

        // Accent- and case-insensitive search on title and first lines; filter by collection.
        mvc.perform(get("/api/songs").param("q", "REFUGIO").with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$.songs[?(@.id == '%s')]", song).exists());
        mvc.perform(get("/api/songs").param("collection", "hinario-teste").with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.songs[0].firstLine").value("Castelo forte é nosso Deus"));

        // A recording (library audio, song purpose) with timing.
        String audio = upload(TestMedia.audio(dir), "castelo.m4a", "SONG_AUDIO");
        String detail = send(post("/api/songs/" + song + "/recordings"), tech(), """
                {"kind":"SUNG","mediaId":"%s","timing":{"source":"MANUAL","reviewed":true,
                 "cues":[{"slide":0,"startMs":0},{"slide":1,"startMs":1000},{"slide":4,"startMs":2500}]}}
                """.formatted(audio)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recordings[0].timing.cues.length()").value(3))
                .andExpect(jsonPath("$.recordings[0].timingCurrent").value(true))
                .andReturn().getResponse().getContentAsString();
        String recording = json.readTree(detail).at("/recordings/0/id").asString();

        send(put("/api/songs/" + song + "/recordings/" + recording + "/timing"), tech(),
                "{\"cues\":[{\"slide\":9,\"startMs\":0}]}").andExpect(status().isBadRequest());

        // Changing the lyrics flags the timing as out of date; resending the same collections works.
        send(put("/api/songs/" + song), tech(), """
                {"title":"Castelo Forte","status":"READY","lyrics":%s,
                 "collections":[{"slug":"hinario-teste","name":"Hinário Teste","group":"Hinários","number":"34"}]}
                """.formatted(LYRICS.replace("\"c\",\"c\"", "\"c\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collections[0].number").value("34"))
                .andExpect(jsonPath("$.lyricsVersion").value(2))
                .andExpect(jsonPath("$.recordings[0].timingCurrent").value(false));

        // Song files stay out of the default library list, and cannot be deleted while used.
        mvc.perform(get("/api/media").with(tech())).andExpect(jsonPath("$[?(@.id == '%s')]", audio).doesNotExist());
        mvc.perform(get("/api/media").param("purpose", "SONG_AUDIO").with(tech()))
                .andExpect(jsonPath("$[?(@.id == '%s')]", audio).exists());
        mvc.perform(delete("/api/media/" + audio).with(tech()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.usages[0]").value("música \"Castelo Forte\" (gravação)"));

        mvc.perform(delete("/api/songs/" + song).with(tech())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/" + audio).with(tech())).andExpect(status().isNoContent());
    }

    @Test
    void importIsIdempotentAndUploadsAreDeduplicated() throws Exception {
        // Unique content: the generated test files may already be in the shared test database.
        byte[] audio = Files.readAllBytes(TestMedia.tone(dir, 200 + (int) (System.nanoTime() % 5000)));
        String first = json.readTree(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "a.m4a",
                        "audio/mp4", audio)).param("purpose", "SONG_AUDIO").with(tech()))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asString();
        // Same content again: same asset, 200.
        mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "copia.m4a", "audio/mp4", audio))
                        .param("purpose", "SONG_AUDIO").with(tech()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));

        String request = """
                {"origin":"louvorja","externalId":"42","song":{"title":"Importada %s","status":"READY","lyrics":%s},
                 "recordings":[{"kind":"SUNG","mediaId":"%s","timing":{"source":"IMPORT","reviewed":true,
                   "cues":[{"slide":0,"startMs":500}]}}]}""";
        String id1 = id(send(post("/api/songs/import"), tech(), request.formatted("v1", LYRICS, first)));
        String id2 = id(send(post("/api/songs/import"), tech(), request.formatted("v2", LYRICS, first))
                .andExpect(jsonPath("$.title").value("Importada v2"))
                .andExpect(jsonPath("$.recordings.length()").value(1))
                .andExpect(jsonPath("$.source").value("IMPORT"))
                .andExpect(jsonPath("$.recordings[0].timing.source").value("IMPORT")));
        org.assertj.core.api.Assertions.assertThat(id2).isEqualTo(id1);

        mvc.perform(delete("/api/songs/" + id1).with(tech()));
        mvc.perform(delete("/api/media/" + first).with(tech()));
    }

    @Test
    void musiciansReadButCannotChangeSongs() throws Exception {
        send(post("/api/songs"), user("musico", "MUSICIAN"), "{\"title\":\"x\"}").andExpect(status().isForbidden());
        send(post("/api/songs"), user("lider", "WORSHIP_LEADER"), "{\"title\":\"x\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/song-collections").with(user("musico", "MUSICIAN"))).andExpect(status().isOk());
        send(post("/api/songs"), tech(), "{\"title\":\"x\",\"lyrics\":{\"parts\":[],\"order\":[\"nope\"]}}")
                .andExpect(status().isBadRequest());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String upload(Path file, String name, String purpose) throws Exception {
        return id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", name, "audio/mp4",
                Files.readAllBytes(file))).param("purpose", purpose).with(tech())));
    }

    private static RequestPostProcessor tech() {
        return user("tecnico", "AV_TECHNICIAN");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
