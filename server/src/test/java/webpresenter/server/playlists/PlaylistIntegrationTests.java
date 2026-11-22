package webpresenter.server.playlists;

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
class PlaylistIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @TempDir
    Path dir;

    @Test
    void leadersBuildPlaylistsOfSongsAndMedia() throws Exception {
        String music = upload(TestMedia.tone(dir, 1200 + (int) (System.nanoTime() % 900)), "piano.m4a", "audio/mp4", "BACKGROUND_MUSIC");
        String image = upload(TestMedia.image(dir), "foto.png", "image/png", "GENERAL");
        String song = id(send(post("/api/songs"), user("tecnico", "AV_TECHNICIAN"), """
                {"title":"Canção da playlist","lyrics":{"parts":[{"id":"v","kind":"VERSE","label":"E","slides":[{"lines":["x"]}]}],"order":["v"]}}"""));

        String playlist = id(send(post("/api/playlists"), leader(), "{\"name\":\"Recepção\",\"shuffle\":true}")
                .andExpect(status().isCreated()));
        send(put("/api/playlists/" + playlist + "/items"), leader(), """
                {"items":[{"type":"MEDIA","mediaId":"%s"},{"type":"SONG","songId":"%s"}]}""".formatted(music, song))
                .andExpect(jsonPath("$.items[0].title").value("piano"))
                .andExpect(jsonPath("$.items[0].kind").value("AUDIO"))
                .andExpect(jsonPath("$.items[1].title").value("Canção da playlist"))
                .andExpect(jsonPath("$.shuffle").value(true));

        send(put("/api/playlists/" + playlist + "/items"), leader(), "{\"items\":[{\"type\":\"MEDIA\",\"mediaId\":\"%s\"}]}".formatted(image))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/playlists").with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$[?(@.id == '%s')].itemCount", playlist).value(2));
        mvc.perform(delete("/api/media/" + music).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.usages[0]").value("playlist \"Recepção\""));
        send(post("/api/playlists"), user("musico", "MUSICIAN"), "{\"name\":\"x\"}").andExpect(status().isForbidden());

        mvc.perform(delete("/api/playlists/" + playlist).with(leader())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/media/" + music).with(user("tecnico", "AV_TECHNICIAN"))).andExpect(status().isNoContent());
    }

    private String upload(Path file, String name, String type, String purpose) throws Exception {
        return id(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", name, type, Files.readAllBytes(file)))
                .param("purpose", purpose).with(user("tecnico", "AV_TECHNICIAN"))));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private static RequestPostProcessor leader() {
        return user("lider", "WORSHIP_LEADER");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
