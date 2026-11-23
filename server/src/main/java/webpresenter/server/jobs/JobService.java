package webpresenter.server.jobs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaFile;
import webpresenter.server.library.MediaImporter;
import webpresenter.server.library.MediaPurpose;
import webpresenter.server.songs.RecordingKind;
import webpresenter.server.songs.SongCatalog;
import webpresenter.server.songs.SongPipeline;
import webpresenter.server.storage.MediaStore;

/** Creates jobs, sends them to the workers, follows their progress and registers their results. */
@Service
class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    /** Jobs may wait in the queue for a long time (Demucs on CPU); links last up to S3's limit of 7 days. */
    private static final Duration LINK_VALIDITY = Duration.ofDays(7);
    /** A running job that reports nothing for this long is considered lost (workers report every 30 s). */
    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    enum ImportTarget { MEDIA, BACKGROUND_MUSIC, SOUND_EFFECT, SONG }

    enum ImportFormat { AUDIO, VIDEO }

    private final JobRepository jobs;
    private final JobDispatcher dispatcher;
    private final JobStreams streams;
    private final MediaStore store;
    private final MediaCatalog catalog;
    private final MediaImporter importer;
    private final SongCatalog songs;
    private final SongPipeline pipeline;
    private final JsonMapper json;
    private final Clock clock;
    /** For writes after a commit (the original transaction is already over). */
    private final org.springframework.transaction.support.TransactionTemplate newTransaction;

    JobService(JobRepository jobs, JobDispatcher dispatcher, JobStreams streams, MediaStore store,
            MediaCatalog catalog, MediaImporter importer, SongCatalog songs, SongPipeline pipeline, JsonMapper json,
            java.util.Optional<Clock> clock, org.springframework.transaction.PlatformTransactionManager transactions) {
        this.newTransaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jobs = jobs;
        this.dispatcher = dispatcher;
        this.streams = streams;
        this.store = store;
        this.catalog = catalog;
        this.importer = importer;
        this.songs = songs;
        this.pipeline = pipeline;
        this.json = json;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    List<JobView> recent(int limit) {
        return jobs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, 500))).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    JobView get(UUID id) {
        return view(find(id));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    JobView importFromUrl(String url, ImportTarget target, ImportFormat format, String title, String user) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidJobException("invalid URL");
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new InvalidJobException("the URL must be an http(s) link");
        }
        if (target == ImportTarget.SOUND_EFFECT && format == ImportFormat.VIDEO) {
            throw new InvalidJobException("sound effects are audio");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", uri.toString());
        params.put("target", target.name());
        params.put("format", format.name());
        String cleanTitle = title == null || title.isBlank() ? null : title.strip();
        params.put("title", cleanTitle);
        String display = (cleanTitle != null ? cleanTitle : uri.toString());
        Job job = jobs.save(new Job(JobType.YOUTUBE_IMPORT, "Importar · " + display, json.writeValueAsString(params),
                user, clock.instant()));
        enqueue(job);
        return view(job);
    }

    @Transactional
    JobView separatePlayback(UUID songId, UUID recordingId, String user) {
        var song = songs.find(songId).orElseThrow(() -> new NotFoundException("song " + songId + " not found"));
        var recording = song.recording(recordingId)
                .orElseThrow(() -> new InvalidJobException("recording " + recordingId + " is not a recording of this song"));
        if (recording.kind() == RecordingKind.PLAYBACK) {
            throw new InvalidJobException("choose a recording with vocals (sung)");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("songId", songId.toString());
        params.put("recordingId", recordingId.toString());
        params.put("mediaId", recording.mediaId().toString());
        Job job = jobs.save(new Job(JobType.SEPARATE_PLAYBACK, "Playback sem voz · " + song.title(),
                json.writeValueAsString(params), user, clock.instant()));
        enqueue(job);
        return view(job);
    }

    /**
     * Finds the lyrics' times in a sung recording; transcribes the lyrics too when the song has
     * none yet. Results are drafts to review.
     */
    @Transactional
    JobView transcribeLyrics(UUID songId, UUID recordingId, String user) {
        return transcribeLyrics(songId, recordingId, false, user);
    }

    /** @param fresh transcribe again even if the song has lyrics (they are replaced) */
    @Transactional
    JobView transcribeLyrics(UUID songId, UUID recordingId, boolean fresh, String user) {
        var song = songs.find(songId).orElseThrow(() -> new NotFoundException("song " + songId + " not found"));
        var recording = song.recording(recordingId)
                .orElseThrow(() -> new InvalidJobException("recording " + recordingId + " is not a recording of this song"));
        if (recording.kind() == RecordingKind.PLAYBACK) {
            throw new InvalidJobException("choose a recording with vocals (sung)");
        }
        List<String> slides = fresh ? List.of() : pipeline.slideTexts(songId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("songId", songId.toString());
        params.put("recordingId", recordingId.toString());
        params.put("mediaId", recording.mediaId().toString());
        params.put("language", "pt");
        params.put("title", song.title()); // helps recognition with the song's own words
        if (!slides.isEmpty()) {
            params.put("slides", slides);
        }
        Job job = jobs.save(new Job(JobType.TRANSCRIBE_LYRICS,
                (slides.isEmpty() ? "Letra e tempos · " : "Tempos da letra · ") + song.title(),
                json.writeValueAsString(params), user, clock.instant()));
        enqueue(job);
        return view(job);
    }

    @Transactional
    JobView cancel(UUID id) {
        Job job = find(id);
        if (job.status().finished()) {
            throw new InvalidJobException("the job has already finished");
        }
        job.cancelled(clock.instant());
        afterCommit(() -> dispatcher.cancel(id));
        return changed(job);
    }

    @Transactional
    JobView retry(UUID id) {
        Job job = find(id);
        if (job.status() != JobStatus.FAILED && job.status() != JobStatus.CANCELLED) {
            throw new InvalidJobException("only failed or cancelled jobs can be retried");
        }
        job.retry(clock.instant());
        enqueue(job);
        return changed(job);
    }

    @Transactional
    void delete(UUID id) {
        Job job = find(id);
        if (!job.status().finished()) {
            throw new InvalidJobException("cancel the job before removing it");
        }
        jobs.delete(job);
        afterCommit(() -> streams.removed(id));
    }

    // ------------------------------------------------------------------ worker events

    /** Applies a worker's report; results are registered in the library (and songs) when a job is done. */
    @Transactional
    void onWorkerEvent(WorkerMessages.WorkerEvent event) {
        Job job = jobs.findById(event.jobId()).orElse(null);
        if (job == null || event.attempt() != job.attempt()) {
            log.debug("ignoring event for unknown job or old attempt: {}", event);
            return;
        }
        if (job.status().finished()) {
            if (job.status() == JobStatus.CANCELLED && "RUNNING".equals(event.status())) {
                afterCommit(() -> dispatcher.cancel(job.id())); // the worker started it before hearing about the cancellation
            }
            return;
        }
        switch (event.status()) {
            case "RUNNING" -> job.running(event.progress() == null ? job.progress() : event.progress(), event.message(),
                    clock.instant());
            case "FAILED" -> job.failed(event.message() == null ? "Falha no processamento" : event.message(), clock.instant());
            case "DONE" -> complete(job, event);
            default -> log.warn("unknown worker status {}", event.status());
        }
        changed(job);
    }

    /** Running jobs whose worker stopped reporting fail, so they can be retried. */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    @Transactional
    void failStaleJobs() {
        for (Job job : jobs.findAllByStatusAndUpdatedAtBefore(JobStatus.RUNNING, clock.instant().minus(STALE_AFTER))) {
            job.failed("O worker parou de responder", clock.instant());
            changed(job);
        }
    }

    private void complete(Job job, WorkerMessages.WorkerEvent event) {
        String staging = stagingKey(job);
        Path temp = null;
        try {
            String filename = event.filename() == null || event.filename().isBlank() ? "resultado" : event.filename();
            temp = Files.createTempFile("webpresenter-job-", suffix(filename));
            store.download(staging, temp);
            Map<String, Object> params = json.readValue(job.params(), MAP);
            Map<String, Object> result = switch (job.type()) {
                case YOUTUBE_IMPORT -> registerImport(job, params, temp, filename, event.title());
                case SEPARATE_PLAYBACK -> registerPlayback(job, params, temp, filename);
                case TRANSCRIBE_LYRICS -> registerLyrics(params, temp);
            };
            job.done(json.writeValueAsString(result), "Concluído", clock.instant());
        } catch (RuntimeException | IOException e) {
            log.warn("could not register the result of job {}", job.id(), e);
            job.failed("Não foi possível registrar o resultado: " + e.getMessage(), clock.instant());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // temporary file
                }
            }
            try {
                store.delete(staging);
            } catch (RuntimeException e) {
                log.debug("could not remove staging object {}", staging, e);
            }
        }
    }

    private Map<String, Object> registerImport(Job job, Map<String, Object> params, Path file, String filename,
            String foundTitle) {
        ImportTarget target = ImportTarget.valueOf((String) params.get("target"));
        String title = params.get("title") instanceof String t ? t : foundTitle != null ? foundTitle : stem(filename);
        MediaPurpose purpose = switch (target) {
            case MEDIA -> MediaPurpose.GENERAL;
            case BACKGROUND_MUSIC -> MediaPurpose.BACKGROUND_MUSIC;
            case SOUND_EFFECT -> MediaPurpose.SOUND_EFFECT;
            case SONG -> MediaPurpose.SONG_AUDIO;
        };
        MediaFile media = importer.importFile(file, filename, title, purpose, job.createdBy());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mediaId", media.id().toString());
        if (target == ImportTarget.SONG) {
            result.put("songId", pipeline.createFromRecording(title, media.id()).toString());
        }
        return result;
    }

    private Map<String, Object> registerPlayback(Job job, Map<String, Object> params, Path file, String filename) {
        UUID songId = UUID.fromString((String) params.get("songId"));
        UUID recordingId = UUID.fromString((String) params.get("recordingId"));
        String songTitle = songs.find(songId).map(SongCatalog.SongPlan::title)
                .orElseThrow(() -> new IllegalStateException("a música foi removida"));
        MediaFile media = importer.importFile(file, filename, songTitle + " (playback gerado)", MediaPurpose.SONG_AUDIO,
                job.createdBy());
        UUID playback = pipeline.addGeneratedPlayback(songId, recordingId, media.id());
        return Map.of("mediaId", media.id().toString(), "songId", songId.toString(), "recordingId", playback.toString());
    }

    /** The worker's JSON: {"mode", "lyrics"?, "cues": [{"slide", "startMs"}], "confidence"?}. */
    private Map<String, Object> registerLyrics(Map<String, Object> params, Path file) throws IOException {
        tools.jackson.databind.JsonNode found = json.readTree(Files.readString(file));
        UUID songId = UUID.fromString((String) params.get("songId"));
        UUID recordingId = UUID.fromString((String) params.get("recordingId"));
        var lyrics = found.get("lyrics");
        List<SongPipeline.GeneratedCue> cues = new java.util.ArrayList<>();
        for (var cue : found.get("cues")) {
            cues.add(new SongPipeline.GeneratedCue(cue.get("slide").asInt(), cue.get("startMs").asLong()));
        }
        int slides = pipeline.applyGeneratedLyrics(songId, recordingId,
                lyrics == null || lyrics.isNull() ? null : json.writeValueAsString(lyrics), cues);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("songId", songId.toString());
        result.put("mode", found.path("mode").asString(""));
        result.put("slides", slides);
        result.put("timedSlides", cues.size());
        var confidence = found.get("confidence");
        if (confidence != null && !confidence.isNull()) {
            result.put("confidence", confidence.asDouble());
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private void enqueue(Job job) {
        Map<String, Object> params = json.readValue(job.params(), MAP);
        String inputUrl = null;
        if (job.type() == JobType.SEPARATE_PLAYBACK || job.type() == JobType.TRANSCRIBE_LYRICS) {
            inputUrl = catalog.workerDownloadUrl(UUID.fromString((String) params.get("mediaId"))).url().toString();
        }
        var request = new WorkerMessages.JobRequest(job.id(), job.attempt(), job.type(), params, inputUrl,
                store.presignedWorkerUpload(stagingKey(job), LINK_VALIDITY).url().toString());
        UUID id = job.id();
        int attempt = job.attempt();
        afterCommit(() -> {
            try {
                dispatcher.dispatch(request);
            } catch (RuntimeException e) {
                log.warn("could not queue job {}", id, e);
                markUnqueued(id, attempt);
            }
        });
    }

    /** The queue is unreachable: the job fails at once, with a message saying why, and can be retried. */
    private void markUnqueued(UUID id, int attempt) {
        JobView failed = newTransaction.execute(status -> jobs.findById(id)
                .filter(j -> j.attempt() == attempt && j.status() == JobStatus.QUEUED)
                .map(job -> {
                    job.failed("Fila de processamento indisponível (RabbitMQ); tente novamente", clock.instant());
                    return view(job);
                })
                .orElse(null));
        if (failed != null) {
            streams.publish(failed);
        }
    }

    private JobView changed(Job job) {
        JobView view = view(job);
        afterCommit(() -> streams.publish(view));
        return view;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    JobView view(Job job) {
        return new JobView(job.id(), job.type(), job.status(), job.title(), json.readValue(job.params(), MAP),
                job.result() == null ? null : json.readValue(job.result(), MAP), job.progress(), job.message(),
                job.attempt(), job.createdBy(), job.createdAt(), job.startedAt(), job.finishedAt(), job.updatedAt());
    }

    static String stagingKey(Job job) {
        return "jobs/" + job.id() + "/" + job.attempt() + "/result";
    }

    private Job find(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
    }

    private static String suffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 && filename.length() - dot <= 6 ? filename.substring(dot) : "";
    }

    private static String stem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    static class InvalidJobException extends RuntimeException {
        InvalidJobException(String message) {
            super(message);
        }
    }
}
