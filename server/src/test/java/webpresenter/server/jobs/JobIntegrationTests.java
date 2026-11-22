package webpresenter.server.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.TestInfrastructure;
import webpresenter.server.TestMedia;

/** The queue with a fake worker: it uses the presigned links for real (SeaweedFS) and reports like a worker. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, JobIntegrationTests.FakeQueue.class})
class JobIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeQueue {
        @Bean
        @Primary
        RecordingDispatcher recordingDispatcher() {
            return new RecordingDispatcher();
        }
    }

    /** Captures what would go to RabbitMQ; can simulate an unreachable queue. */
    static class RecordingDispatcher implements JobDispatcher {
        final List<WorkerMessages.JobRequest> sent = new CopyOnWriteArrayList<>();
        final List<UUID> cancelled = new CopyOnWriteArrayList<>();
        volatile boolean down;

        @Override
        public void dispatch(WorkerMessages.JobRequest request) {
            if (down) {
                throw new IllegalStateException("connection refused");
            }
            sent.add(request);
        }

        @Override
        public void cancel(UUID jobId) {
            cancelled.add(jobId);
        }

        WorkerMessages.JobRequest last(UUID jobId) {
            return sent.reversed().stream().filter(r -> r.jobId().equals(jobId)).findFirst().orElseThrow();
        }
    }

    static final String LYRICS = """
            {"parts":[{"id":"v","kind":"VERSE","label":"E","slides":[{"lines":["um"]},{"lines":["dois"]}]}],"order":["v"]}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    RecordingDispatcher queue;

    @Autowired
    JobService jobs;

    @TempDir
    Path dir;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        queue.down = false;
    }

    @Test
    void separatesVocalsIntoAPlaybackRecordingWithTheSameTiming() throws Exception {
        String sung = upload(TestMedia.tone(dir, 300 + (int) (System.nanoTime() % 400)), "cantada.m4a", "SONG_AUDIO");
        UUID song = id(send(post("/api/songs"), "{\"title\":\"Separar vozes\",\"lyrics\":" + LYRICS + "}"));
        JsonNode created = body(send(post("/api/songs/" + song + "/recordings"), """
                {"kind":"SUNG","mediaId":"%s","timing":{"source":"IMPORT","reviewed":true,
                 "cues":[{"slide":0,"startMs":1000},{"slide":1,"startMs":5000}]}}""".formatted(sung)));
        UUID recording = UUID.fromString(created.get("recordings").get(0).get("id").asString());

        UUID job = id(send(post("/api/jobs/separate-playback"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, recording))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.title").value("Playback sem voz · Separar vozes")));

        // The worker downloads the sung recording and uploads its result through the presigned links.
        var request = queue.last(job);
        assertThat(request.type()).isEqualTo(JobType.SEPARATE_PLAYBACK);
        HttpResponse<byte[]> input = http.send(HttpRequest.newBuilder(URI.create(request.inputUrl())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(input.statusCode()).isEqualTo(200);
        assertThat(input.body()).hasSizeGreaterThan(1000);

        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "RUNNING", 40, "Separando vozes", null, null));
        mvc.perform(get("/api/jobs/" + job).with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.progress").value(40))
                .andExpect(jsonPath("$.message").value("Separando vozes"));

        putResult(request, TestMedia.tone(dir, 900 + (int) (System.nanoTime() % 400)));
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "DONE", 100, null, "no_vocals.m4a", null));

        JsonNode done = body(mvc.perform(get("/api/jobs/" + job).with(tech())));
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        String playback = done.get("result").get("recordingId").asString();

        JsonNode detail = body(mvc.perform(get("/api/songs/" + song).with(tech())));
        JsonNode generated = detail.get("recordings").valueStream()
                .filter(r -> r.get("id").asString().equals(playback)).findFirst().orElseThrow();
        assertThat(generated.get("kind").asString()).isEqualTo("PLAYBACK");
        assertThat(generated.get("source").asString()).isEqualTo("PIPELINE");
        assertThat(generated.get("media").get("title").asString()).isEqualTo("Separar vozes (playback gerado)");
        assertThat(generated.get("timing").get("reviewed").asBoolean()).isFalse();
        assertThat(generated.get("timing").get("cues").get(1).get("startMs").asLong()).isEqualTo(5000);
        assertThat(generated.get("timingCurrent").asBoolean()).isTrue();

        // A playback cannot be separated again.
        send(post("/api/jobs/separate-playback"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}".formatted(song, playback))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importsFromALinkAsBackgroundMusicOrAsANewSong() throws Exception {
        UUID music = id(send(post("/api/jobs/youtube-import"), """
                {"url":"https://www.youtube.com/watch?v=Xx1MjhzKcYw","target":"BACKGROUND_MUSIC"}""")
                .andExpect(jsonPath("$.params.format").value("AUDIO")));
        var request = queue.last(music);
        assertThat(request.params()).containsEntry("url", "https://www.youtube.com/watch?v=Xx1MjhzKcYw");
        assertThat(request.inputUrl()).isNull();
        putResult(request, TestMedia.tone(dir, 1400 + (int) (System.nanoTime() % 400)));
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(music, 1, "DONE", 100, null, "piano.m4a", "Piano instrumental"));

        JsonNode result = body(mvc.perform(get("/api/jobs/" + music).with(tech()))).get("result");
        mvc.perform(get("/api/media").param("purpose", "BACKGROUND_MUSIC").with(tech()))
                .andExpect(jsonPath("$[?(@.id == '%s')].title", result.get("mediaId").asString()).value("Piano instrumental"));

        UUID song = id(send(post("/api/jobs/youtube-import"), """
                {"url":"https://youtu.be/abc","target":"SONG","title":"Canção baixada"}"""));
        putResult(queue.last(song), TestMedia.tone(dir, 1900 + (int) (System.nanoTime() % 400)));
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(song, 1, "DONE", 100, null, "video.m4a", "Outro título"));
        String songId = body(mvc.perform(get("/api/jobs/" + song).with(tech()))).get("result").get("songId").asString();
        mvc.perform(get("/api/songs/" + songId).with(tech()))
                .andExpect(jsonPath("$.title").value("Canção baixada"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.recordings[0].kind").value("SUNG"));
    }

    @Test
    void cancelRetryFailureAndUnreachableQueue() throws Exception {
        UUID job = id(send(post("/api/jobs/youtube-import"), "{\"url\":\"https://youtu.be/x\",\"target\":\"MEDIA\",\"format\":\"VIDEO\"}"));
        mvc.perform(post("/api/jobs/" + job + "/cancel").with(tech())).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(queue.cancelled).contains(job);

        // A worker that started it anyway is told again; the job stays cancelled.
        queue.cancelled.clear();
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "RUNNING", 5, "Baixando", null, null));
        assertThat(queue.cancelled).containsExactly(job);
        mvc.perform(get("/api/jobs/" + job).with(tech())).andExpect(jsonPath("$.status").value("CANCELLED"));

        // Retry: a new attempt; reports of the old one are ignored.
        mvc.perform(post("/api/jobs/" + job + "/retry").with(tech()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempt").value(2));
        assertThat(queue.last(job).attempt()).isEqualTo(2);
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "FAILED", null, "velho", null, null));
        mvc.perform(get("/api/jobs/" + job).with(tech())).andExpect(jsonPath("$.status").value("QUEUED"));
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 2, "FAILED", null, "Vídeo indisponível", null, null));
        mvc.perform(get("/api/jobs/" + job).with(tech()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Vídeo indisponível"));
        mvc.perform(delete("/api/jobs/" + job).with(tech())).andExpect(status().isNoContent());

        // RabbitMQ down: the job fails at once with a clear message.
        queue.down = true;
        UUID lost = id(send(post("/api/jobs/youtube-import"), "{\"url\":\"https://youtu.be/y\",\"target\":\"MEDIA\"}"));
        mvc.perform(get("/api/jobs/" + lost).with(tech()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("indisponível")));
    }

    @Test
    void validatesAndRestricts() throws Exception {
        send(post("/api/jobs/youtube-import"), "{\"url\":\"ftp://x/y\",\"target\":\"MEDIA\"}").andExpect(status().isBadRequest());
        send(post("/api/jobs/youtube-import"), "{\"url\":\"https://youtu.be/x\",\"target\":\"SOUND_EFFECT\",\"format\":\"VIDEO\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/jobs/youtube-import").with(user("lider", "WORSHIP_LEADER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://youtu.be/x\",\"target\":\"MEDIA\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/jobs").with(user("musico", "MUSICIAN"))).andExpect(status().isOk());
    }

    private void putResult(WorkerMessages.JobRequest request, Path file) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(request.outputUrl()))
                .PUT(HttpRequest.BodyPublishers.ofFile(file)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private String upload(Path file, String name, String purpose) throws Exception {
        return body(mvc.perform(multipart("/api/media").file(new MockMultipartFile("file", name, "audio/mp4",
                Files.readAllBytes(file))).param("purpose", purpose).with(tech()))).get("id").asString();
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body) throws Exception {
        return mvc.perform(request.with(tech()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID id(ResultActions result) throws Exception {
        return UUID.fromString(body(result).get("id").asString());
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static RequestPostProcessor tech() {
        return user("tecnico", "AV_TECHNICIAN");
    }

    private static RequestPostProcessor user(String username, String role) {
        return jwt().jwt(j -> j.subject(username).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
