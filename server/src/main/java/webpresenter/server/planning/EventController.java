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

/** Event planning. Everyone signed in can read; leaders, AV technicians and admins can edit. */
@RestController
@RequestMapping("/api/events")
class EventController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN', 'AV_TECHNICIAN', 'WORSHIP_LEADER')";

    private final PlanningService planning;
    private final MediaCatalog catalog;

    EventController(PlanningService planning, MediaCatalog catalog) {
        this.planning = planning;
        this.catalog = catalog;
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
        });
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
        List<ItemView> items = event.items().stream().map(i -> {
            MediaView media = i.type() != ItemType.MEDIA ? null : catalog.find(i.mediaId())
                    .map(m -> new MediaView(m.id(), m.title(), m.kind(), m.durationMs()))
                    .orElse(new MediaView(i.mediaId(), "(mídia removida)", null, null));
            String title = i.type() == ItemType.SLIDE ? i.slideTitle() : media.title();
            SlideView slide = i.type() == ItemType.SLIDE ? new SlideView(i.slideTitle(), i.slideBody()) : null;
            return new ItemView(i.id(), i.type(), title, media, slide);
        }).toList();
        return new EventDetail(event.id(), event.title(), event.startsAt(), event.current(), items,
                event.createdBy(), event.updatedAt());
    }

    // ------------------------------------------------------------------ requests and views

    record EventRequest(@NotBlank @Size(max = 200) String title, Instant startsAt) {}

    record NewItemRequest(@NotNull ItemType type, UUID mediaId, @Size(max = 200) String slideTitle,
            @Size(max = 4000) String slideBody, Integer position) {}

    record SlideRequest(@NotBlank @Size(max = 200) String slideTitle, @Size(max = 4000) String slideBody) {}

    record OrderRequest(@NotEmpty List<UUID> itemIds) {}

    record EventSummary(UUID id, String title, Instant startsAt, boolean current, int itemCount, Instant updatedAt) {
        static EventSummary of(PlannedEvent e) {
            return new EventSummary(e.id(), e.title(), e.startsAt(), e.current(), e.items().size(), e.updatedAt());
        }
    }

    record EventDetail(UUID id, String title, Instant startsAt, boolean current, List<ItemView> items,
            String createdBy, Instant updatedAt) {}

    record ItemView(UUID id, ItemType type, String title, MediaView media, SlideView slide) {}

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
