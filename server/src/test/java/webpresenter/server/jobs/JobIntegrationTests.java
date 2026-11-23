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
import java.util.Map;
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
    void separatesTracksWithTheSameTimingAndReplacesThemWhenRunAgain() throws Exception {
        String sung = upload(tone(), "cantada.m4a", "SONG_AUDIO");
        UUID song = id(send(post("/api/songs"), "{\"title\":\"Separar vozes\",\"lyrics\":" + LYRICS + "}"));
        JsonNode created = body(send(post("/api/songs/" + song + "/recordings"), """
                {"kind":"SUNG","mediaId":"%s","timing":{"source":"IMPORT","reviewed":true,
                 "cues":[{"slide":0,"startMs":1000},{"slide":1,"startMs":5000}]}}""".formatted(sung)));
        UUID recording = UUID.fromString(created.get("recordings").get(0).get("id").asString());

        UUID job = id(send(post("/api/jobs/separate-playback"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, recording))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.title").value("Separar faixas · Separar vozes")));

        // The worker downloads the sung recording and uploads each track through its presigned link.
        var request = queue.last(job);
        assertThat(request.type()).isEqualTo(JobType.SEPARATE_PLAYBACK);
        assertThat(request.outputs()).containsOnlyKeys("playback", "vocals", "drums", "bass", "other");
        HttpResponse<byte[]> input = http.send(HttpRequest.newBuilder(URI.create(request.inputUrl())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(input.statusCode()).isEqualTo(200);
        assertThat(input.body()).hasSizeGreaterThan(1000);

        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "RUNNING", 40, "Separando as faixas", null, null));
        mvc.perform(get("/api/jobs/" + job).with(user("musico", "MUSICIAN")))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.progress").value(40))
                .andExpect(jsonPath("$.message").value("Separando as faixas"));

        finishTracks(job, request, "playback", "vocals", "drums", "bass", "other");
        JsonNode done = body(mvc.perform(get("/api/jobs/" + job).with(tech())));
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        assertThat(done.get("result").get("recordingIds")).hasSize(5);

        JsonNode detail = body(mvc.perform(get("/api/songs/" + song).with(tech())));
        JsonNode playback = track(detail, "PLAYBACK", null);
        assertThat(playback.get("source").asString()).isEqualTo("PIPELINE");
        assertThat(playback.get("media").get("title").asString()).isEqualTo("Separar vozes (playback gerado)");
        assertThat(playback.get("derivedFrom").asString()).isEqualTo(recording.toString());
        for (String part : List.of("VOCALS", "DRUMS", "BASS", "OTHER")) {
            JsonNode stem = track(detail, "STEM", part);
            assertThat(stem.get("derivedFrom").asString()).isEqualTo(recording.toString());
            assertThat(stem.get("timing").get("reviewed").asBoolean()).isFalse();
            assertThat(stem.get("timing").get("cues").get(1).get("startMs").asLong()).isEqualTo(5000);
            assertThat(stem.get("timingCurrent").asBoolean()).isTrue();
        }
        assertThat(track(detail, "STEM", "VOCALS").get("media").get("title").asString()).isEqualTo("Separar vozes (voz)");
        String oldVocalsMedia = track(detail, "STEM", "VOCALS").get("media").get("id").asString();

        // A playback or a separated track cannot be separated again.
        send(post("/api/jobs/separate-playback"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, playback.get("id").asString())).andExpect(status().isBadRequest());
        send(post("/api/jobs/separate-playback"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, track(detail, "STEM", "DRUMS").get("id").asString())).andExpect(status().isBadRequest());

        // Running it again replaces the tracks; their old media leave the library.
        UUID again = id(send(post("/api/jobs/separate-playback"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, recording)));
        finishTracks(again, queue.last(again), "playback", "vocals", "drums", "bass", "other");
        JsonNode rerun = body(mvc.perform(get("/api/jobs/" + again).with(tech())));
        assertThat(rerun.get("result").get("replaced").asInt()).isEqualTo(5);
        detail = body(mvc.perform(get("/api/songs/" + song).with(tech())));
        assertThat(detail.get("recordings")).hasSize(6);
        mvc.perform(get("/api/media/" + oldVocalsMedia).with(tech())).andExpect(status().isNotFound());

        // Choir voices come from the vocals track, not from instruments.
        String vocals = track(detail, "STEM", "VOCALS").get("id").asString();
        send(post("/api/jobs/separate-voices"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, track(detail, "STEM", "BASS").get("id").asString())).andExpect(status().isBadRequest());
        UUID voices = id(send(post("/api/jobs/separate-voices"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, vocals))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Separar naipes · Separar vozes")));
        var voicesRequest = queue.last(voices);
        assertThat(voicesRequest.outputs()).containsKeys("soprano", "alto", "tenor", "bass_voice");
        finishTracks(voices, voicesRequest, "soprano", "alto", "tenor", "bass_voice");
        detail = body(mvc.perform(get("/api/songs/" + song).with(tech())));
        JsonNode soprano = track(detail, "STEM", "SOPRANO");
        assertThat(soprano.get("derivedFrom").asString()).isEqualTo(vocals);
        assertThat(soprano.get("media").get("title").asString()).isEqualTo("Separar vozes (soprano)");
        assertThat(soprano.get("timing").get("cues").get(1).get("startMs").asLong()).isEqualTo(5000);
        assertThat(track(detail, "STEM", "BASS_VOICE")).isNotNull();
    }

    /** The worker uploads a file per output and reports them. */
    private void finishTracks(UUID job, WorkerMessages.JobRequest request, String... names) throws Exception {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (String name : names) {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(request.outputs().get(name)))
                    .PUT(HttpRequest.BodyPublishers.ofFile(tone())).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
            files.put(name, name + ".m4a");
        }
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, request.attempt(), "DONE", 100, null, null, null, files));
    }

    private static JsonNode track(JsonNode detail, String kind, String part) {
        return detail.get("recordings").valueStream()
                .filter(r -> r.get("kind").asString().equals(kind)
                        && (part == null || part.equals(r.path("part").asString(null))))
                .findFirst().orElseThrow(() -> new AssertionError("no " + kind + " " + part));
    }

    private static final java.util.concurrent.atomic.AtomicInteger TONES =
            new java.util.concurrent.atomic.AtomicInteger((int) (System.nanoTime() % 1000));

    /** A distinct tone each time (the library refuses duplicates by content). */
    private Path tone() {
        return TestMedia.tone(dir, 2000 + TONES.getAndAdd(7));
    }

    @Test
    void transcribesLyricsOfASongWithoutThemAndTimesItsGeneratedPlaybackToo() throws Exception {
        String sung = upload(TestMedia.tone(dir, 700 + (int) (System.nanoTime() % 300)), "vinde.m4a", "SONG_AUDIO");
        UUID song = id(send(post("/api/songs"), """
                {"title":"Vinde a Mim","lyrics":{"parts":[{"id":"i","kind":"INSTRUMENTAL","label":"Instrumental",
                 "slides":[{"lines":[]}]}],"order":["i"]}}"""));
        UUID recording = UUID.fromString(body(send(post("/api/songs/" + song + "/recordings"),
                "{\"kind\":\"SUNG\",\"mediaId\":\"%s\"}".formatted(sung))).get("recordings").get(0).get("id").asString());
        // A playback generated before the lyrics existed.
        UUID separation = id(send(post("/api/jobs/separate-playback"),
                "{\"songId\":\"%s\",\"recordingId\":\"%s\"}".formatted(song, recording)));
        putResult(queue.last(separation), TestMedia.tone(dir, 1100 + (int) (System.nanoTime() % 300)));
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(separation, 1, "DONE", 100, null, "playback.m4a", null));

        UUID job = id(send(post("/api/jobs/transcribe-lyrics"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, recording))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Letra e tempos · Vinde a Mim")));
        var request = queue.last(job);
        assertThat(request.type()).isEqualTo(JobType.TRANSCRIBE_LYRICS);
        assertThat(request.params()).doesNotContainKey("slides"); // no lyrics yet: transcribe
        assertThat(request.inputUrl()).isNotNull();

        putJson(request, """
                {"mode":"transcribed","language":"pt","confidence":null,
                 "lyrics":{"parts":[{"id":"v1","kind":"VERSE","label":"Estrofe 1","slides":[{"lines":["Vinde a mim","Os cansados"]}]},
                                    {"id":"c","kind":"CHORUS","label":"Coro","slides":[{"lines":["Tomai o meu jugo"]}]}],
                           "order":["v1","c","c"]},
                 "cues":[{"slide":0,"startMs":9000},{"slide":1,"startMs":20000},{"slide":2,"startMs":41000}]}""");
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "DONE", 100, null, "lyrics.json", null));

        mvc.perform(get("/api/jobs/" + job).with(tech()))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.mode").value("transcribed"))
                .andExpect(jsonPath("$.result.slides").value(3));
        JsonNode detail = body(mvc.perform(get("/api/songs/" + song).with(tech())));
        assertThat(detail.get("status").asString()).isEqualTo("DRAFT");
        assertThat(detail.get("slideSequence")).hasSize(3);
        assertThat(detail.get("recordings")).hasSize(2);
        for (JsonNode r : detail.get("recordings")) {
            // The sung recording and the playback generated from it share the timeline.
            assertThat(r.get("timing").get("cues").get(2).get("startMs").asLong()).isEqualTo(41000);
            assertThat(r.get("timing").get("source").asString()).isEqualTo("PIPELINE");
            assertThat(r.get("timing").get("reviewed").asBoolean()).isFalse();
            assertThat(r.get("timingCurrent").asBoolean()).isTrue();
        }
    }

    @Test
    void alignsTheLyricsASongAlreadyHas() throws Exception {
        String sung = upload(TestMedia.tone(dir, 1700 + (int) (System.nanoTime() % 200)), "hino.m4a", "SONG_AUDIO");
        UUID song = id(send(post("/api/songs"), "{\"title\":\"Hino com letra\",\"lyrics\":" + LYRICS + "}"));
        UUID recording = UUID.fromString(body(send(post("/api/songs/" + song + "/recordings"),
                "{\"kind\":\"SUNG\",\"mediaId\":\"%s\"}".formatted(sung))).get("recordings").get(0).get("id").asString());

        UUID job = id(send(post("/api/jobs/transcribe-lyrics"), "{\"songId\":\"%s\",\"recordingId\":\"%s\"}"
                .formatted(song, recording)).andExpect(jsonPath("$.title").value("Tempos da letra · Hino com letra")));
        assertThat(queue.last(job).params()).containsEntry("slides", java.util.List.of("um", "dois"));
        putJson(queue.last(job),
                "{\"mode\":\"aligned\",\"confidence\":0.86,\"cues\":[{\"slide\":0,\"startMs\":3000},{\"slide\":1,\"startMs\":7500}]}");
        jobs.onWorkerEvent(new WorkerMessages.WorkerEvent(job, 1, "DONE", 100, null, "lyrics.json", null));

        mvc.perform(get("/api/jobs/" + job).with(tech())).andExpect(jsonPath("$.result.confidence").value(0.86));
        mvc.perform(get("/api/songs/" + song).with(tech()))
                .andExpect(jsonPath("$.lyrics.parts[0].slides[0].lines[0]").value("um")) // lyrics kept
                .andExpect(jsonPath("$.recordings[0].timing.cues[1].startMs").value(7500));
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

    private void putJson(WorkerMessages.JobRequest request, String content) throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(request.outputUrl()))
                .PUT(HttpRequest.BodyPublishers.ofString(content)).build(), HttpResponse.BodyHandlers.discarding());
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
