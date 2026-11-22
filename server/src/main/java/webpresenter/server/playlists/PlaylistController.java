package webpresenter.server.playlists;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaKind;
import webpresenter.server.songs.SongCatalog;

/** Playlists: everyone reads; leaders, AV technicians and admins change. */
@RestController
@RequestMapping("/api/playlists")
class PlaylistController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN', 'AV_TECHNICIAN', 'WORSHIP_LEADER')";

    private final PlaylistService service;
    private final SongCatalog songs;
    private final MediaCatalog catalog;

    PlaylistController(PlaylistService service, SongCatalog songs, MediaCatalog catalog) {
        this.service = service;
        this.songs = songs;
        this.catalog = catalog;
    }

    @GetMapping
    List<PlaylistSummary> list() {
        return service.list().stream()
                .map(p -> new PlaylistSummary(p.id(), p.name(), p.description(), p.shuffle(), p.items().size(), p.updatedAt()))
                .toList();
    }

    @GetMapping("/{id}")
    PlaylistDetail get(@PathVariable UUID id) {
        return detail(service.get(id));
    }

    @PostMapping
    @PreAuthorize(CAN_EDIT)
    ResponseEntity<PlaylistDetail> create(@Valid @RequestBody PlaylistRequest request, Authentication user) {
        Playlist playlist = service.create(request.name(), request.description(), request.shuffleOrFalse(), user.getName());
        return ResponseEntity.created(URI.create("/api/playlists/" + playlist.id())).body(detail(playlist));
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    PlaylistDetail update(@PathVariable UUID id, @Valid @RequestBody PlaylistRequest request) {
        return detail(service.update(id, request.name(), request.description(), request.shuffleOrFalse()));
    }

    @PutMapping("/{id}/items")
    @PreAuthorize(CAN_EDIT)
    PlaylistDetail setItems(@PathVariable UUID id, @Valid @RequestBody ItemsRequest request) {
        return detail(service.setItems(id, request.items()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    record PlaylistRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            Boolean shuffle) {

        boolean shuffleOrFalse() {
            return Boolean.TRUE.equals(shuffle);
        }
    }

    record ItemsRequest(@NotNull List<PlaylistItem.Spec> items) {}

    record PlaylistSummary(UUID id, String name, String description, boolean shuffle, int itemCount, Instant updatedAt) {}

    /** @param kind AUDIO or VIDEO for media items; null for songs */
    record ItemView(UUID id, PlaylistItem.Type type, String title, UUID songId, UUID recordingId, String recordingKind,
            UUID mediaId, MediaKind kind, Long durationMs) {}

    record PlaylistDetail(UUID id, String name, String description, boolean shuffle, List<ItemView> items,
            String createdBy, Instant updatedAt) {}

    private PlaylistDetail detail(Playlist p) {
        List<ItemView> items = p.items().stream().map(i -> switch (i.type()) {
            case SONG -> songs.find(i.songId()).map(song -> {
                var recording = i.recordingId() != null ? song.recording(i.recordingId()).orElse(null)
                        : song.recordings().stream().filter(r -> r.kind().name().equals("SUNG")).findFirst()
                                .orElse(song.recordings().isEmpty() ? null : song.recordings().getFirst());
                Long duration = recording == null ? null : catalog.find(recording.mediaId()).map(m -> m.durationMs()).orElse(null);
                return new ItemView(i.id(), i.type(), song.title(), song.id(), i.recordingId(),
                        recording == null ? null : recording.kind().name(), null, null, duration);
            }).orElse(new ItemView(i.id(), i.type(), "(música removida)", i.songId(), i.recordingId(), null, null, null, null));
            case MEDIA -> catalog.find(i.mediaId())
                    .map(m -> new ItemView(i.id(), i.type(), m.title(), null, null, null, m.id(), m.kind(), m.durationMs()))
                    .orElse(new ItemView(i.id(), i.type(), "(mídia removida)", null, null, null, i.mediaId(), null, null));
        }).toList();
        return new PlaylistDetail(p.id(), p.name(), p.description(), p.shuffle(), items, p.createdBy(), p.updatedAt());
    }

    @ExceptionHandler(PlaylistService.NotFoundException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PlaylistService.InvalidPlaylistException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
