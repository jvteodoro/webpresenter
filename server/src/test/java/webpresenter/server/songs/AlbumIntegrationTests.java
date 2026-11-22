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
class AlbumIntegrationTests {

    static final String LYRICS = "{\"parts\":[{\"id\":\"v\",\"kind\":\"VERSE\",\"label\":\"E\",\"slides\":[{\"lines\":[\"x\"]}]}],\"order\":[\"v\"]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    @Test
    void createsAlbumsWithCoversAndOrderedTracks() throws Exception {
        String cover = id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", "capa.png", "image/png",
                Files.readAllBytes(TestMedia.image(dir)))).param("purpose", "COVER").with(tech())));
        String first = id(send(post("/api/songs"), tech(), "{\"title\":\"Primeira do álbum\",\"lyrics\":" + LYRICS + "}"));
        String second = id(send(post("/api/songs"), tech(), "{\"title\":\"Segunda do álbum\",\"lyrics\":" + LYRICS + "}"));
        String loose = id(send(post("/api/songs"), tech(), "{\"title\":\"Avulsa única\",\"lyrics\":" + LYRICS + "}"));

        String album = id(send(post("/api/albums"), tech(), """
                {"name":"Louvores da Juventude","description":"Coletânea","coverMediaId":"%s"}""".formatted(cover))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.coverUrl").value(org.hamcrest.Matchers.startsWith("http"))));

        send(put("/api/albums/" + album + "/tracks"), tech(), "{\"songIds\":[\"%s\",\"%s\"]}".formatted(second, first))
                .andExpect(jsonPath("$.tracks[0].title").value("Segunda do álbum"))
                .andExpect(jsonPath("$.tracks[0].number").value("1"))
                .andExpect(jsonPath("$.tracks[1].number").value("2"))
                .andExpect(jsonPath("$.album.trackCount").value(2));

        mvc.perform(get("/api/albums").with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$[?(@.id == '%s')].trackCount", album).value(2));

        // Loose songs: in no album.
        mvc.perform(get("/api/songs").param("loose", "true").param("q", "do álbum").with(tech()))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/songs").param("loose", "true").param("q", "avulsa única").with(tech()))
                .andExpect(jsonPath("$.songs[0].id").value(loose));

        // The cover is in use; covers must be images.
        mvc.perform(delete("/api/media/" + cover).with(tech())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.usages[0]").value("álbum \"Louvores da Juventude\" (capa)"));
        send(put("/api/albums/" + album), tech(), "{\"name\":\"X\",\"coverMediaId\":\"%s\"}".formatted(java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest());

        send(post("/api/albums"), user("musico", "MUSICIAN"), "{\"name\":\"x\"}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/albums/" + album).with(tech())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/" + cover).with(tech())).andExpect(status().isNoContent());
    }

    @Test
    void importedAlbumsCannotBeDeleted() throws Exception {
        String song = id(send(post("/api/songs/import"), tech(), """
                {"origin":"teste","externalId":"%s","song":{"title":"Importada","lyrics":%s,
                 "collections":[{"slug":"importado-x","name":"Álbum importado","number":"3"}]}}"""
                .formatted(System.nanoTime(), LYRICS)));
        String album = json.readTree(mvc.perform(get("/api/albums").with(tech())).andReturn().getResponse().getContentAsString())
                .valueStream().filter(a -> a.get("slug").asString().equals("importado-x")).findFirst().orElseThrow()
                .get("id").asString();
        mvc.perform(get("/api/albums/" + album).with(tech()))
                .andExpect(jsonPath("$.album.source").value("IMPORT"))
                .andExpect(jsonPath("$.tracks[0].songId").value(song));
        mvc.perform(delete("/api/albums/" + album).with(tech())).andExpect(status().isBadRequest());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private static RequestPostProcessor tech() {
        return user("tecnico", "AV_TECHNICIAN");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
