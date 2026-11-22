package webpresenter.server.planning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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
import webpresenter.server.playlists.PlaylistCatalog;
import webpresenter.server.songs.SongCatalog;

/** Liturgy templates: everyone reads; leaders, AV technicians and admins change them and create events. */
@RestController
@RequestMapping("/api/liturgy/templates")
class LiturgyController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN', 'AV_TECHNICIAN', 'WORSHIP_LEADER')";

    private final LiturgyService liturgy;
    private final MediaCatalog catalog;
    private final SongCatalog songs;
    private final PlaylistCatalog playlists;

    LiturgyController(LiturgyService liturgy, MediaCatalog catalog, SongCatalog songs, PlaylistCatalog playlists) {
        this.liturgy = liturgy;
        this.catalog = catalog;
        this.songs = songs;
        this.playlists = playlists;
    }

    @GetMapping
    List<TemplateSummary> list() {
        return liturgy.list().stream().map(t -> new TemplateSummary(t.id(), t.name(), t.days(), t.startTime(),
                liturgy.slots(t).size(), (int) liturgy.slots(t).stream().filter(s -> s.type() == ItemType.SLOT).count(),
                t.updatedAt())).toList();
    }

    @GetMapping("/{id}")
    TemplateDetail get(@PathVariable UUID id) {
        return detail(liturgy.get(id));
    }

    @PostMapping
    @PreAuthorize(CAN_EDIT)
    ResponseEntity<TemplateDetail> create(@Valid @RequestBody TemplateRequest request, Authentication user) {
        LiturgyTemplate t = liturgy.create(request.name(), days(request.weekdays()), request.startTime(), user.getName());
        return ResponseEntity.created(URI.create("/api/liturgy/templates/" + t.id())).body(detail(t));
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    TemplateDetail update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return detail(liturgy.update(id, request.name(), days(request.weekdays()), request.startTime()));
    }

    @PutMapping("/{id}/slots")
    @PreAuthorize(CAN_EDIT)
    TemplateDetail setSlots(@PathVariable UUID id, @Valid @RequestBody SlotsRequest request) {
        return detail(liturgy.setSlots(id, request.slots()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        liturgy.delete(id);
    }

    /** Creates the event of this template for a date (409 with its id when it already exists). */
    @PostMapping("/{id}/events")
    @PreAuthorize(CAN_EDIT)
    ResponseEntity<Map<String, Object>> createEvent(@PathVariable UUID id, @Valid @RequestBody NewEventRequest request,
            Authentication user) {
        PlannedEvent event = liturgy.createEvent(id, request.date(), request.startsAt(), request.title(), user.getName());
        return ResponseEntity.created(URI.create("/api/events/" + event.id()))
                .body(Map.of("id", event.id(), "title", event.title(), "pendingSlots", event.pendingSlots()));
    }

    // ------------------------------------------------------------------ requests and views

    /** @param weekdays ISO days: 1 = Monday … 7 = Sunday */
    record TemplateRequest(@NotBlank @Size(max = 200) String name, List<Integer> weekdays, LocalTime startTime) {}

    record SlotsRequest(@NotNull List<ItemSpec> slots) {}

    /** @param startsAt the date and start time in the caller's local time, or null */
    record NewEventRequest(@NotNull LocalDate date, Instant startsAt, @Size(max = 200) String title) {}

    record TemplateSummary(UUID id, String name, List<DayOfWeek> weekdays, LocalTime startTime, int slotCount,
            int variableSlots, Instant updatedAt) {}

    /** A slot with what people need to read it: a title (song, media, slide or slot name) and a description. */
    record SlotView(ItemSpec spec, String title, String detail, String backgroundName) {}

    record TemplateDetail(UUID id, String name, List<DayOfWeek> weekdays, LocalTime startTime, List<SlotView> slots,
            Instant updatedAt) {}

    private TemplateDetail detail(LiturgyTemplate t) {
        List<SlotView> slots = liturgy.slots(t).stream().map(this::slotView).toList();
        return new TemplateDetail(t.id(), t.name(), t.days(), t.startTime(), slots, t.updatedAt());
    }

    private SlotView slotView(ItemSpec s) {
        String background = s.background() == null ? null : playlists.describe(s.background());
        return switch (s.type()) {
            case MEDIA -> new SlotView(s, catalog.find(s.mediaId()).map(m -> m.title()).orElse("(mídia removida)"),
                    "Mídia", background);
            case SLIDE -> new SlotView(s, s.slideTitle(), "Slide", background);
            case SONG -> new SlotView(s, songs.find(s.songId()).map(SongCatalog.SongPlan::title).orElse("(música removida)"),
                    s.recordingId() == null ? "Música · sem áudio" : "Música", background);
            case SCENE -> new SlotView(s, s.sceneTitle(), "Cena", background);
            case CLOCK -> new SlotView(s, s.label() == null ? "Relógio" : s.label(), "Relógio", background);
            case TIMER -> new SlotView(s, s.label() == null ? "Cronômetro" : s.label(), "Cronômetro", background);
            case SLOT -> new SlotView(s, s.label(), "A escolher a cada evento", background);
        };
    }

    private static List<DayOfWeek> days(List<Integer> weekdays) {
        if (weekdays == null) {
            return List.of();
        }
        try {
            return weekdays.stream().distinct().map(DayOfWeek::of).toList();
        } catch (java.time.DateTimeException e) {
            throw new PlanningService.InvalidRequestException("weekdays are 1 (Monday) to 7 (Sunday)");
        }
    }

    @ExceptionHandler(PlanningService.NotFoundException.class)
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PlanningService.InvalidRequestException.class)
    ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(LiturgyService.EventExistsException.class)
    ProblemDetail exists(LiturgyService.EventExistsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("eventId", e.eventId);
        return problem;
    }
}
