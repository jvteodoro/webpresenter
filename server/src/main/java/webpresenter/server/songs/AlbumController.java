package webpresenter.server.songs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import webpresenter.server.library.MediaCatalog;

/** Albums: everyone reads; AV technicians and admins change. */
@RestController
@RequestMapping("/api/albums")
class AlbumController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN', 'AV_TECHNICIAN')";

    private final AlbumService albums;
    private final SongService songs;
    private final MediaCatalog catalog;

    AlbumController(AlbumService albums, SongService songs, MediaCatalog catalog) {
        this.albums = albums;
        this.songs = songs;
        this.catalog = catalog;
    }

    @GetMapping
    List<AlbumView> list() {
        return albums.list().stream().map(r -> view(r.album(), r.trackCount())).toList();
    }

    @GetMapping("/{id}")
    AlbumDetail get(@PathVariable UUID id) {
        SongCollection album = albums.get(id);
        List<TrackView> tracks = albums.tracks(id).stream().map(t -> new TrackView(t.number(), t.song().id(),
                t.song().title(), t.song().recordings().stream().map(Recording::kind).distinct().toList())).toList();
        return new AlbumDetail(view(album, tracks.size()), tracks);
    }

    @PostMapping
    @PreAuthorize(CAN_EDIT)
    ResponseEntity<AlbumView> create(@Valid @RequestBody AlbumRequest request) {
        SongCollection album = albums.create(request.name(), request.description(), request.group(), request.coverMediaId());
        return ResponseEntity.created(URI.create("/api/albums/" + album.id())).body(view(album, 0));
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    AlbumView update(@PathVariable UUID id, @Valid @RequestBody AlbumRequest request) {
        SongCollection album = albums.update(id, request.name(), request.description(), request.group(), request.coverMediaId());
        return view(album, albums.tracks(id).size());
    }

    @PutMapping("/{id}/cover")
    @PreAuthorize(CAN_EDIT)
    AlbumView setCover(@PathVariable UUID id, @RequestBody CoverRequest request) {
        return view(albums.setCover(id, request.coverMediaId()), albums.tracks(id).size());
    }

    @PutMapping("/{id}/tracks")
    @PreAuthorize(CAN_EDIT)
    AlbumDetail setTracks(@PathVariable UUID id, @Valid @RequestBody TracksRequest request) {
        albums.setTracks(id, request.songIds());
        return get(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        albums.delete(id);
    }

    record AlbumRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @Size(max = 100) String group, UUID coverMediaId) {}

    record CoverRequest(UUID coverMediaId) {}

    record TracksRequest(@NotNull List<UUID> songIds) {}

    /** @param coverUrl short-lived direct link to the cover for browsers, or null */
    record AlbumView(UUID id, String slug, String name, String description, String group, Source source,
            UUID coverMediaId, String coverUrl, long trackCount) {}

    record TrackView(String number, UUID songId, String title, List<RecordingKind> recordings) {}

    record AlbumDetail(AlbumView album, List<TrackView> tracks) {}

    @org.springframework.web.bind.annotation.ExceptionHandler(SongService.NotFoundException.class)
    org.springframework.http.ProblemDetail notFound(RuntimeException e) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SongService.InvalidSongException.class)
    org.springframework.http.ProblemDetail invalid(RuntimeException e) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private AlbumView view(SongCollection album, long trackCount) {
        String coverUrl = album.coverMediaId() == null ? null
                : catalog.find(album.coverMediaId()).map(m -> catalog.downloadUrl(m.id()).url().toString()).orElse(null);
        return new AlbumView(album.id(), album.slug(), album.name(), album.description(), album.groupName(),
                album.source(), album.coverMediaId(), coverUrl, trackCount);
    }
}
