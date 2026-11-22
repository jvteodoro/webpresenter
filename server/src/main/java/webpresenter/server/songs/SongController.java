package webpresenter.server.songs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaKind;

/** Songs: everyone signed in reads; AV technicians and admins change. */
@RestController
@RequestMapping("/api")
class SongController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN', 'AV_TECHNICIAN')";

    private final SongService service;
    private final MediaCatalog catalog;

    SongController(SongService service, MediaCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @GetMapping("/songs")
    SongPage search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String collection,
            @RequestParam(defaultValue = "false") boolean loose,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        var page = service.search(q, collection, loose, offset, limit);
        return new SongPage(page.songs().stream().map(this::summary).toList(), page.total(), offset);
    }

    @GetMapping("/songs/{id}")
    SongDetail get(@PathVariable UUID id) {
        return detail(service.get(id));
    }

    @GetMapping("/song-collections")
    List<CollectionView> collections() {
        return service.collections().stream()
                .map(c -> new CollectionView(c.slug(), c.name(), c.groupName())).toList();
    }

    @PostMapping("/songs")
    @PreAuthorize(CAN_EDIT)
    ResponseEntity<SongDetail> create(@Valid @RequestBody SongService.SongInput input) {
        Song song = service.create(input, Source.MANUAL);
        return ResponseEntity.created(URI.create("/api/songs/" + song.id())).body(detail(song));
    }

    @PutMapping("/songs/{id}")
    @PreAuthorize(CAN_EDIT)
    SongDetail update(@PathVariable UUID id, @Valid @RequestBody SongService.SongInput input) {
        return detail(service.update(id, input));
    }

    @DeleteMapping("/songs/{id}")
    @PreAuthorize(CAN_EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/songs/{id}/recordings")
    @PreAuthorize(CAN_EDIT)
    SongDetail addRecording(@PathVariable UUID id, @Valid @RequestBody SongService.RecordingInput input) {
        return detail(service.addRecording(id, input));
    }

    @PutMapping("/songs/{id}/recordings/{recordingId}/timing")
    @PreAuthorize(CAN_EDIT)
    SongDetail setTiming(@PathVariable UUID id, @PathVariable UUID recordingId, @RequestBody Timing timing) {
        return detail(service.setTiming(id, recordingId, timing));
    }

    @DeleteMapping("/songs/{id}/recordings/{recordingId}")
    @PreAuthorize(CAN_EDIT)
    SongDetail removeRecording(@PathVariable UUID id, @PathVariable UUID recordingId) {
        return detail(service.removeRecording(id, recordingId));
    }

    /** Idempotent import from another tool: matched by origin + external id. */
    @PostMapping("/songs/import")
    @PreAuthorize(CAN_EDIT)
    SongDetail importSong(@Valid @RequestBody ImportRequest request) {
        return detail(service.importSong(request.origin(), request.externalId(), request.song(),
                request.recordings() == null ? List.of() : request.recordings()));
    }

    // ------------------------------------------------------------------ views

    record ImportRequest(@NotBlank String origin, @NotBlank String externalId, @NotNull SongService.SongInput song,
            List<SongService.RecordingInput> recordings) {}

    record SongPage(List<SongSummary> songs, long total, int offset) {}

    record CollectionView(String slug, String name, String group) {}

    record CollectionRef(String slug, String name, String group, String number) {}

    record SongSummary(UUID id, String title, String language, SongStatus status, List<CollectionRef> collections,
            List<RecordingKind> recordings, int slideCount, String firstLine) {}

    record MediaRef(UUID id, String title, MediaKind kind, Long durationMs) {}

    record RecordingView(UUID id, RecordingKind kind, Source source, MediaRef media, Timing timing,
            boolean timingCurrent) {}

    record SongDetail(UUID id, String title, String alternativeTitles, String language, String authors,
            String copyright, SongStatus status, Source source, String origin, String externalId,
            MediaRef background, List<CollectionRef> collections, Lyrics lyrics, int lyricsVersion,
            List<Lyrics.SequenceSlide> slideSequence, List<RecordingView> recordings, Instant updatedAt) {}

    private SongSummary summary(Song song) {
        List<Lyrics.SequenceSlide> sequence = service.lyrics(song).slideSequence();
        String firstLine = sequence.stream().flatMap(s -> s.lines().stream()).filter(l -> !l.isBlank())
                .findFirst().orElse(null);
        return new SongSummary(song.id(), song.title(), song.language(), song.status(), collectionRefs(song),
                song.recordings().stream().map(Recording::kind).distinct().toList(), sequence.size(), firstLine);
    }

    private SongDetail detail(Song song) {
        Lyrics lyrics = service.lyrics(song);
        List<RecordingView> recordings = song.recordings().stream().map(r -> {
            Timing timing = service.timing(r);
            return new RecordingView(r.id(), r.kind(), r.source(), media(r.mediaId()), timing,
                    timing != null && java.util.Objects.equals(timing.lyricsVersion(), song.lyricsVersion()));
        }).toList();
        return new SongDetail(song.id(), song.title(), song.alternativeTitles(), song.language(), song.authors(),
                song.copyright(), song.status(), song.source(), song.origin(), song.externalId(),
                song.backgroundMediaId() == null ? null : media(song.backgroundMediaId()), collectionRefs(song),
                lyrics, song.lyricsVersion(), lyrics.slideSequence(), recordings, song.updatedAt());
    }

    private List<CollectionRef> collectionRefs(Song song) {
        return song.collections().stream()
                .map(e -> new CollectionRef(e.collection().slug(), e.collection().name(), e.collection().groupName(),
                        e.number()))
                .toList();
    }

    private MediaRef media(UUID id) {
        return catalog.find(id).map(m -> new MediaRef(m.id(), m.title(), m.kind(), m.durationMs()))
                .orElse(new MediaRef(id, "(mídia removida)", null, null));
    }

    @ExceptionHandler(SongService.NotFoundException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SongService.InvalidSongException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrent(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the song was changed by someone else at the same time; reload it and try again");
    }
}
