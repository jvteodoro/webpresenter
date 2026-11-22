package webpresenter.server.planning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

/** Event planning. Everyone signed in can read; leaders, AV technicians and admins can edit. */
@RestController
@RequestMapping("/api/events")
class EventController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN', 'AV_TECHNICIAN', 'WORSHIP_LEADER')";

    private final PlanningService planning;
    private final MediaCatalog catalog;
    private final SongCatalog songs;
    private final webpresenter.server.playlists.PlaylistCatalog playlists;

    EventController(PlanningService planning, MediaCatalog catalog, SongCatalog songs,
            webpresenter.server.playlists.PlaylistCatalog playlists) {
        this.planning = planning;
        this.catalog = catalog;
        this.songs = songs;
        this.playlists = playlists;
    }

    @GetMapping
    List<EventSummary> list() {
        return planning.list().stream().map(EventSummary::of).toList();
    }

    @PostMapping
    @PreAuthorize(CAN_EDIT)
    ResponseEntity<EventDetail> create(@Valid @RequestBody EventRequest request, Authentication user) {
        PlannedEvent event = planning.create(request.title(), request.startsAt(), user.getName());
        return ResponseEntity.created(URI.create("/api/events/" + event.id())).body(detail(event));
    }

    @GetMapping("/{id}")
    EventDetail get(@PathVariable UUID id) {
        return detail(planning.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    EventDetail update(@PathVariable UUID id, @Valid @RequestBody EventRequest request) {
        return detail(planning.update(id, request.title(), request.startsAt()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        planning.delete(id);
    }

    @PostMapping("/{id}/items")
    @PreAuthorize(CAN_EDIT)
    EventDetail addItem(@PathVariable UUID id, @Valid @RequestBody NewItemRequest request) {
        return detail(switch (request.type()) {
            case MEDIA -> {
                if (request.mediaId() == null) {
                    throw new PlanningService.InvalidRequestException("mediaId is required for media items");
                }
                yield planning.addMedia(id, request.mediaId(), request.position());
            }
            case SLIDE -> {
                if (request.slideTitle() == null || request.slideTitle().isBlank()) {
                    throw new PlanningService.InvalidRequestException("slideTitle is required for text slides");
                }
                yield planning.addSlide(id, request.slideTitle(), request.slideBody(), request.position());
            }
            case SONG -> {
                if (request.songId() == null) {
                    throw new PlanningService.InvalidRequestException("songId is required for songs");
                }
                yield planning.addSong(id, request.songId(), request.recordingId(), request.position());
            }
            case SCENE -> {
                if (request.sceneTitle() == null || request.sceneTitle().isBlank()) {
                    throw new PlanningService.InvalidRequestException("sceneTitle is required for scenes");
                }
                yield planning.addScene(id, request.sceneTitle(), request.sceneStarts(), request.sceneEnds(),
                        request.position());
            }
        });
    }

    @PutMapping("/{id}/items/{itemId}/scene")
    @PreAuthorize(CAN_EDIT)
    EventDetail editScene(@PathVariable UUID id, @PathVariable UUID itemId, @Valid @RequestBody SceneRequest request) {
        return detail(planning.editScene(id, itemId, request.title(), request.starts(), request.ends()));
    }

    /** Background music of an item or a scene ({@code source} null: inherit). */
    @PutMapping("/{id}/items/{itemId}/background")
    @PreAuthorize(CAN_EDIT)
    EventDetail setItemBackground(@PathVariable UUID id, @PathVariable UUID itemId, @RequestBody BackgroundRequest request) {
        return detail(planning.setItemBackground(id, itemId, request.source()));
    }

    /** Background music of the event ({@code source} null: the platform's). */
    @PutMapping("/{id}/background")
    @PreAuthorize(CAN_EDIT)
    EventDetail setEventBackground(@PathVariable UUID id, @RequestBody BackgroundRequest request) {
        return detail(planning.setEventBackground(id, request.source()));
    }

    /** Chooses which recording of the song plays (null: lyrics only). */
    @PutMapping("/{id}/items/{itemId}/recording")
    @PreAuthorize(CAN_EDIT)
    EventDetail chooseRecording(@PathVariable UUID id, @PathVariable UUID itemId, @RequestBody RecordingChoice choice) {
        return detail(planning.chooseRecording(id, itemId, choice.recordingId()));
    }

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize(CAN_EDIT)
    EventDetail editSlide(@PathVariable UUID id, @PathVariable UUID itemId, @Valid @RequestBody SlideRequest request) {
        return detail(planning.editSlide(id, itemId, request.slideTitle(), request.slideBody()));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize(CAN_EDIT)
    EventDetail removeItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        return detail(planning.removeItem(id, itemId));
    }

    @PutMapping("/{id}/items/order")
    @PreAuthorize(CAN_EDIT)
    EventDetail reorder(@PathVariable UUID id, @Valid @RequestBody OrderRequest request) {
        return detail(planning.reorder(id, request.itemIds()));
    }

    /** Makes this the current event: players download all its media (again, for failed items). */
    @PostMapping("/{id}/current")
    @PreAuthorize(CAN_EDIT)
    EventDetail makeCurrent(@PathVariable UUID id) {
        return detail(planning.makeCurrent(id));
    }

    @DeleteMapping("/current")
    @PreAuthorize(CAN_EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearCurrent() {
        planning.clearCurrent();
    }

    private EventDetail detail(PlannedEvent event) {
        List<ItemView> items = event.items().stream().map(i -> switch (i.type()) {
            case MEDIA -> {
                MediaView media = catalog.find(i.mediaId())
                        .map(m -> new MediaView(m.id(), m.title(), m.kind(), m.durationMs()))
                        .orElse(new MediaView(i.mediaId(), "(mídia removida)", null, null));
                yield new ItemView(i.id(), i.type(), media.title(), media, null, null, null, background(i.background()));
            }
            case SLIDE -> new ItemView(i.id(), i.type(), i.slideTitle(), null, new SlideView(i.slideTitle(), i.slideBody()),
                    null, null, background(i.background()));
            case SCENE -> new ItemView(i.id(), i.type(), i.sceneTitle(), null, null, null,
                    new SceneView(i.sceneTitle(), i.sceneStarts(), i.sceneEnds()), background(i.background()));
            case SONG -> {
                SongView song = songs.find(i.songId()).map(plan -> {
                    var recording = i.recordingId() == null ? null : plan.recording(i.recordingId()).orElse(null);
                    Long duration = recording == null ? null : catalog.find(recording.mediaId()).map(m -> m.durationMs()).orElse(null);
                    return new SongView(plan.id(), plan.title(), plan.slides().size(),
                            recording == null ? null : recording.id(), recording == null ? null : recording.kind().name(),
                            recording != null && recording.cueStartMs().stream().anyMatch(java.util.Objects::nonNull),
                            duration, plan.recordings().stream()
                                    .map(r -> new RecordingOption(r.id(), r.kind().name(),
                                            r.cueStartMs().stream().anyMatch(java.util.Objects::nonNull)))
                                    .toList());
                }).orElse(new SongView(i.songId(), "(música removida)", 0, null, null, false, null, List.of()));
                yield new ItemView(i.id(), i.type(), song.title(), null, null, song, null, background(i.background()));
            }
        }).toList();
        return new EventDetail(event.id(), event.title(), event.startsAt(), event.current(), items,
                event.createdBy(), event.updatedAt(), background(event.background()));
    }

    private BackgroundView background(webpresenter.server.playlists.BackgroundSource source) {
        return source == null ? null : new BackgroundView(source, playlists.describe(source));
    }

    // ------------------------------------------------------------------ requests and views

    record EventRequest(@NotBlank @Size(max = 200) String title, Instant startsAt) {}

    record NewItemRequest(@NotNull ItemType type, UUID mediaId, @Size(max = 200) String slideTitle,
            @Size(max = 4000) String slideBody, UUID songId, UUID recordingId, @Size(max = 200) String sceneTitle,
            java.time.LocalTime sceneStarts, java.time.LocalTime sceneEnds, Integer position) {}

    record SceneRequest(@NotBlank @Size(max = 200) String title, java.time.LocalTime starts, java.time.LocalTime ends) {}

    record BackgroundRequest(webpresenter.server.playlists.BackgroundSource source) {}

    /** @param name display name ("Playlist Recepção", a track title, "Silêncio") */
    record BackgroundView(webpresenter.server.playlists.BackgroundSource source, String name) {}

    record SceneView(String title, java.time.LocalTime starts, java.time.LocalTime ends) {}

    record RecordingChoice(UUID recordingId) {}

    record SlideRequest(@NotBlank @Size(max = 200) String slideTitle, @Size(max = 4000) String slideBody) {}

    record OrderRequest(@NotEmpty List<UUID> itemIds) {}

    record EventSummary(UUID id, String title, Instant startsAt, boolean current, int itemCount, Instant updatedAt) {
        static EventSummary of(PlannedEvent e) {
            return new EventSummary(e.id(), e.title(), e.startsAt(), e.current(), e.items().size(), e.updatedAt());
        }
    }

    /** @param background the event's own background music, or null (platform default) */
    record EventDetail(UUID id, String title, Instant startsAt, boolean current, List<ItemView> items,
            String createdBy, Instant updatedAt, BackgroundView background) {}

    /** @param background the item's (or scene's) own background music, or null (inherited) */
    record ItemView(UUID id, ItemType type, String title, MediaView media, SlideView slide, SongView song,
            SceneView scene, BackgroundView background) {}

    /** A song item: chosen recording (null: lyrics only) and the recordings available. */
    record SongView(UUID id, String title, int slideCount, UUID recordingId, String recordingKind, boolean timed,
            Long durationMs, List<RecordingOption> recordings) {}

    record RecordingOption(UUID id, String kind, boolean timed) {}

    record MediaView(UUID id, String title, MediaKind kind, Long durationMs) {}

    record SlideView(String title, String body) {}

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(PlanningService.NotFoundException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentEdit(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the event was changed by someone else at the same time; reload it and try again");
    }

    @ExceptionHandler(PlanningService.InvalidRequestException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
