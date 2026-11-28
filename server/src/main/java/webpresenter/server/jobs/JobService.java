package webpresenter.server.jobs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import webpresenter.server.songs.StemPart;
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

    enum ImportTarget { MEDIA, BACKGROUND_MUSIC, SOUND_EFFECT, SONG, MISC, MUSICAL_MESSAGE }

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

    @Transactional(readOnly = true)
    Optional<JobView> lookup(UUID id) {
        return jobs.findById(id).map(this::view);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    JobView importFromUrl(String url, ImportTarget target, ImportFormat format, String title, String user) {
        return importFromUrl(url, target, format, title, null, user);
    }

    /**
     * @param matchDate for a channel: the worker downloads the video of this day (found by title or upload
     *     date); null when the URL is the video itself
     */
    @Transactional
    JobView importFromUrl(String url, ImportTarget target, ImportFormat format, String title, LocalDate matchDate,
            String user) {
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
        if (matchDate != null) {
            params.put("matchDate", matchDate.toString());
        }
        String display = (cleanTitle != null ? cleanTitle : uri.toString());
        Job job = jobs.save(new Job(JobType.YOUTUBE_IMPORT, "Importar · " + display, json.writeValueAsString(params),
                user, clock.instant()));
        enqueue(job);
        return view(job);
    }

    /**
     * Lyrics and slide times read from the on-screen text of a video (the song's own lyrics when it has
     * slides, aligned to them; otherwise transcribed from the text). The video's timeline must be the
     * recording's: the recording is the performance the video shows.
     */
    @Transactional
    JobView extractLyricsFromVideo(UUID songId, UUID recordingId, UUID mediaId, boolean fresh, String user) {
        var song = songs.find(songId).orElseThrow(() -> new NotFoundException("song " + songId + " not found"));
        var recording = song.recording(recordingId)
                .orElseThrow(() -> new InvalidJobException("recording " + recordingId + " is not a recording of this song"));
        if (recording.kind() == RecordingKind.PLAYBACK) {
            throw new InvalidJobException("choose the recording the video follows (sung)");
        }
        var video = catalog.find(mediaId).orElseThrow(() -> new InvalidJobException("video " + mediaId + " not found"));
        if (video.kind() != webpresenter.server.library.MediaKind.VIDEO) {
            throw new InvalidJobException("choose a video");
        }
        List<String> slides = fresh ? List.of() : pipeline.slideTexts(songId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("songId", songId.toString());
        params.put("recordingId", recordingId.toString());
        params.put("mediaId", mediaId.toString());
        params.put("language", "pt");
        params.put("title", song.title());
        if (!slides.isEmpty()) {
            params.put("slides", slides);
        }
        Job job = jobs.save(new Job(JobType.VIDEO_LYRICS,
                (slides.isEmpty() ? "Letra do vídeo · " : "Tempos da letra pelo vídeo · ") + song.title(),
                json.writeValueAsString(params), user, clock.instant()));
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
        if (recording.kind() == RecordingKind.STEM) {
            throw new InvalidJobException("choose the song's recording, not a separated track");
        }
        Job job = jobs.save(new Job(JobType.SEPARATE_PLAYBACK, "Separar faixas · " + song.title(),
                json.writeValueAsString(params), user, clock.instant()));
        enqueue(job);
        return view(job);
    }

    /**
     * Separates the choir voices of a recording: preferably the VOCALS track separated by Demucs
     * (voices alone), or a sung recording that is a cappella.
     */
    @Transactional
    JobView separateVoices(UUID songId, UUID recordingId, String user) {
        var song = songs.find(songId).orElseThrow(() -> new NotFoundException("song " + songId + " not found"));
        UUID media = pipeline.recordingMedia(songId, recordingId)
                .orElseThrow(() -> new InvalidJobException("recording " + recordingId + " is not a recording of this song"));
        StemPart part = pipeline.recordingPart(songId, recordingId).orElse(null);
        boolean usable = part != null ? part == StemPart.VOCALS
                : song.recording(recordingId).map(r -> r.kind() != RecordingKind.PLAYBACK).orElse(false);
        if (!usable) {
            throw new InvalidJobException("choose the vocals track (or a sung a cappella recording)");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("songId", songId.toString());
        params.put("recordingId", recordingId.toString());
        params.put("mediaId", media.toString());
        Job job = jobs.save(new Job(JobType.SEPARATE_VOICES, "Separar naipes · " + song.title(),
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
        if (event.files() != null) {
            completeMany(job, event.files());
            return;
        }
        String staging = stagingKey(job);
        Path temp = null;
        try {
            String filename = event.filename() == null || event.filename().isBlank() ? "resultado" : event.filename();
            temp = Files.createTempFile("webpresenter-job-", suffix(filename));
            store.download(staging, temp);
            Map<String, Object> params = json.readValue(job.params(), MAP);
            Map<String, Object> result = switch (job.type()) {
                case YOUTUBE_IMPORT -> registerImport(job, params, temp, filename, event.title());
                case TRANSCRIBE_LYRICS, VIDEO_LYRICS -> registerLyrics(params, temp);
                case SEPARATE_PLAYBACK -> registerTracks(job, params, Map.of("playback", new Output(temp, filename)));
                case SEPARATE_VOICES -> throw new IllegalStateException("the worker sent a single file");
            };
            job.done(json.writeValueAsString(result), "Concluído", clock.instant());
        } catch (RuntimeException | IOException e) {
            log.warn("could not register the result of job {}", job.id(), e);
            job.failed("Não foi possível registrar o resultado: " + e.getMessage(), clock.instant());
        } finally {
            deleteQuietly(temp);
            removeStaging(staging);
        }
    }

    /** A result file downloaded from the staging area. */
    record Output(Path file, String filename) {}

    private void completeMany(Job job, Map<String, String> files) {
        Map<String, Output> outputs = new LinkedHashMap<>();
        try {
            for (var file : files.entrySet()) {
                if (!outputNames(job).contains(file.getKey())) {
                    throw new IllegalStateException("unexpected result " + file.getKey());
                }
                String filename = file.getValue() == null || file.getValue().isBlank() ? file.getKey() : file.getValue();
                Path temp = Files.createTempFile("webpresenter-job-", suffix(filename));
                outputs.put(file.getKey(), new Output(temp, filename));
                store.download(stagingKey(job, file.getKey()), temp);
            }
            Map<String, Object> params = json.readValue(job.params(), MAP);
            Map<String, Object> result = switch (job.type()) {
                case SEPARATE_PLAYBACK, SEPARATE_VOICES -> registerTracks(job, params, outputs);
                default -> throw new IllegalStateException("the worker sent several files for a " + job.type() + " job");
            };
            job.done(json.writeValueAsString(result), "Concluído", clock.instant());
        } catch (RuntimeException | IOException e) {
            log.warn("could not register the results of job {}", job.id(), e);
            job.failed("Não foi possível registrar o resultado: " + e.getMessage(), clock.instant());
        } finally {
            outputs.values().forEach(o -> deleteQuietly(o.file()));
            outputNames(job).forEach(name -> removeStaging(stagingKey(job, name)));
        }
    }

    /** Output names of jobs with several result files (empty: a single result). */
    static List<String> outputNames(Job job) {
        return switch (job.type()) {
            case SEPARATE_PLAYBACK -> List.of("playback", "vocals", "drums", "bass", "other");
            case SEPARATE_VOICES -> List.of("lead", "soprano", "alto", "tenor", "bass_voice", "vocal_percussion");
            default -> List.of();
        };
    }

    private static final Map<String, String> TRACK_LABELS = Map.ofEntries(
            Map.entry("playback", "playback gerado"), Map.entry("vocals", "voz"), Map.entry("drums", "bateria"),
            Map.entry("bass", "baixo"), Map.entry("other", "outros instrumentos"), Map.entry("lead", "solista"),
            Map.entry("soprano", "soprano"), Map.entry("alto", "contralto"), Map.entry("tenor", "tenor"),
            Map.entry("bass_voice", "baixo, voz"), Map.entry("vocal_percussion", "percussão vocal"));

    /** Separated tracks into the library and the song; replaced tracks' media are discarded. */
    private Map<String, Object> registerTracks(Job job, Map<String, Object> params, Map<String, Output> outputs) {
        UUID songId = UUID.fromString((String) params.get("songId"));
        UUID recordingId = UUID.fromString((String) params.get("recordingId"));
        String songTitle = songs.find(songId).map(SongCatalog.SongPlan::title)
                .orElseThrow(() -> new IllegalStateException("a música foi removida"));
        if (outputs.isEmpty()) {
            throw new IllegalStateException("nenhuma faixa foi gerada");
        }
        UUID playback = null;
        Map<StemPart, UUID> stems = new java.util.EnumMap<>(StemPart.class);
        for (var output : outputs.entrySet()) {
            MediaFile media = importer.importFile(output.getValue().file(), output.getValue().filename(),
                    songTitle + " (" + TRACK_LABELS.getOrDefault(output.getKey(), output.getKey()) + ")",
                    MediaPurpose.SONG_AUDIO, job.createdBy());
            if (output.getKey().equals("playback")) {
                playback = media.id();
            } else {
                stems.put(StemPart.valueOf(output.getKey().toUpperCase(java.util.Locale.ROOT)), media.id());
            }
        }
        var tracks = pipeline.addSeparatedTracks(songId, recordingId, playback, stems);
        int discarded = 0;
        for (UUID old : tracks.replacedMedia()) {
            try {
                discarded += importer.discardIfUnused(old) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("could not discard replaced media {}", old, e);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("songId", songId.toString());
        result.put("recordingIds", tracks.recordingIds().stream().map(UUID::toString).toList());
        result.put("tracks", List.copyOf(outputs.keySet()));
        result.put("replaced", discarded);
        if (playback != null) {
            result.put("mediaId", playback.toString());
            result.put("recordingId", tracks.recordingIds().getFirst().toString()); // the playback comes first
        }
        return result;
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temporary file
            }
        }
    }

    private void removeStaging(String key) {
        try {
            store.delete(key);
        } catch (RuntimeException e) {
            log.debug("could not remove staging object {}", key, e);
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
            case MISC -> MediaPurpose.MISC;
            case MUSICAL_MESSAGE -> MediaPurpose.MUSICAL_MESSAGE;
        };
        MediaFile media = importer.importFile(file, filename, title, purpose, job.createdBy());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mediaId", media.id().toString());
        if (target == ImportTarget.SONG) {
            result.put("songId", pipeline.createFromRecording(title, media.id()).toString());
        }
        return result;
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
        if (job.type() != JobType.YOUTUBE_IMPORT) {
            inputUrl = catalog.workerDownloadUrl(UUID.fromString((String) params.get("mediaId"))).url().toString();
        }
        Map<String, String> outputs = null;
        if (!outputNames(job).isEmpty()) {
            outputs = new LinkedHashMap<>();
            for (String name : outputNames(job)) {
                outputs.put(name, store.presignedWorkerUpload(stagingKey(job, name), LINK_VALIDITY).url().toString());
            }
        }
        var request = new WorkerMessages.JobRequest(job.id(), job.attempt(), job.type(), params, inputUrl,
                store.presignedWorkerUpload(stagingKey(job), LINK_VALIDITY).url().toString(), outputs);
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

    static String stagingKey(Job job, String output) {
        return "jobs/" + job.id() + "/" + job.attempt() + "/" + output;
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
