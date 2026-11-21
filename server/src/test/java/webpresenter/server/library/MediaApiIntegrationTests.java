package webpresenter.server.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeAll;
import webpresenter.server.TestMedia;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.TestInfrastructure;

/** Full upload → list → download → rename → delete cycle against PostgreSQL and SeaweedFS. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class MediaApiIntegrationTests {

    @TempDir
    static Path dir;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void requireFfmpeg() {
        TestMedia.assumeFfmpeg();
    }

    @Test
    void uploadsListsDownloadsRenamesAndDeletesMedia() throws Exception {
        Path video = TestMedia.video(dir);
        byte[] bytes = Files.readAllBytes(video);
        var file = new MockMultipartFile("file", "Abertura do culto.mp4", "video/mp4", bytes);

        String body = mvc.perform(multipart("/api/media").file(file).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.title").value("Abertura do culto"))
                .andExpect(jsonPath("$.kind").value("VIDEO"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.sha256").value(sha256(bytes)))
                .andExpect(jsonPath("$.width").value(320))
                .andExpect(jsonPath("$.createdBy").value("tecnico"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();

        mvc.perform(get("/api/media").with(user("musico", "MUSICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]", id).exists());

        // The presigned link works without any credentials and serves the exact bytes.
        String linkJson = mvc.perform(get("/api/media/{id}/download-url", id).with(user("musico", "MUSICIAN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        URI link = URI.create(json.readTree(linkJson).get("url").asString());
        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(link).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(sha256(download.body())).isEqualTo(sha256(bytes));

        mvc.perform(patch("/api/media/{id}", id).with(user("tecnico", "AV_TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Vinheta de abertura\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Vinheta de abertura"));

        mvc.perform(delete("/api/media/{id}", id).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/media/{id}", id).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isNotFound());
        HttpResponse<Void> afterDelete = http.send(HttpRequest.newBuilder(link).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(afterDelete.statusCode()).isEqualTo(404);
    }

    @Test
    void detectsKindFromContentNotFromFilename() throws Exception {
        byte[] audio = Files.readAllBytes(TestMedia.audio(dir));
        var file = new MockMultipartFile("file", "hino.bin", "application/octet-stream", audio);

        mvc.perform(multipart("/api/media").file(file).with(user("admin", "ADMIN")))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.kind").value("AUDIO"));
    }

    @Test
    void rejectsFilesThatAreNotMedia() throws Exception {
        var file = new MockMultipartFile("file", "planilha.xlsx", "application/octet-stream", "not media".getBytes());

        mvc.perform(multipart("/api/media").file(file).with(user("tecnico", "AV_TECHNICIAN")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void onlyAvTechniciansAndAdminsCanChangeTheLibrary() throws Exception {
        var file = new MockMultipartFile("file", "x.mp4", "video/mp4", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/media").file(file).with(user("lider", "WORSHIP_LEADER")))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/media").file(file).with(user("musico", "MUSICIAN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/media")).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

}
