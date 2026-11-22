package webpresenter.server.planning;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import webpresenter.server.library.MediaUsage;

/** Liturgy templates and the events made from them. */
@Service
class LiturgyService implements MediaUsage {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    private final LiturgyTemplateRepository templates;
    private final PlannedEventRepository events;
    private final PlanningService planning;
    private final JsonMapper json;
    private final Clock clock = Clock.systemUTC();

    LiturgyService(LiturgyTemplateRepository templates, PlannedEventRepository events, PlanningService planning,
            JsonMapper json) {
        this.templates = templates;
        this.events = events;
        this.planning = planning;
        this.json = json;
    }

    @Transactional(readOnly = true)
    List<LiturgyTemplate> list() {
        return templates.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    LiturgyTemplate get(UUID id) {
        return find(id);
    }

    List<ItemSpec> slots(LiturgyTemplate template) {
        return List.of(json.readValue(template.slots(), ItemSpec[].class));
    }

    @Transactional
    LiturgyTemplate create(String name, List<DayOfWeek> days, LocalTime startTime, String user) {
        LiturgyTemplate template = new LiturgyTemplate(name.strip(), user, clock.instant());
        template.describe(name.strip(), days, startTime, clock.instant());
        return templates.save(template);
    }

    @Transactional
    LiturgyTemplate update(UUID id, String name, List<DayOfWeek> days, LocalTime startTime) {
        LiturgyTemplate template = find(id);
        template.describe(name.strip(), days, startTime, clock.instant());
        return template;
    }

    /** Replaces the slots; each is validated as an event item would be. */
    @Transactional
    LiturgyTemplate setSlots(UUID id, List<ItemSpec> slots) {
        LiturgyTemplate template = find(id);
        if (slots.size() > 200) {
            throw new PlanningService.InvalidRequestException("at most 200 slots");
        }
        PlannedEvent scratch = new PlannedEvent("validation", null, "system", clock.instant());
        for (ItemSpec slot : slots) {
            planning.build(scratch, slot); // throws InvalidRequestException with the reason
        }
        template.setSlots(json.writeValueAsString(slots), clock.instant());
        return template;
    }

    @Transactional
    void delete(UUID id) {
        templates.delete(find(id)); // events made from it keep their items (template_id is cleared)
    }

    /**
     * Creates the event of a template for a date: fixed slots copied, variable slots left to be
     * filled. At most one per template and date.
     *
     * @param startsAt computed by the caller in its local time (null: no time)
     */
    @Transactional
    PlannedEvent createEvent(UUID id, LocalDate date, Instant startsAt, String title, String user) {
        LiturgyTemplate template = find(id);
        events.findByTemplateIdAndTemplateDate(id, date).ifPresent(existing -> {
            throw new EventExistsException(existing.id());
        });
        String eventTitle = title == null || title.isBlank() ? template.name() + " · " + DAY.format(date) : title.strip();
        PlannedEvent event = new PlannedEvent(eventTitle, startsAt, user, clock.instant());
        event.fromTemplate(id, date);
        for (ItemSpec slot : slots(template)) {
            event.addItem(planning.build(event, slot), null, clock.instant());
        }
        return events.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID mediaId) {
        List<String> usages = new ArrayList<>();
        for (LiturgyTemplate template : templates.findAll()) {
            boolean uses = slots(template).stream().anyMatch(s -> mediaId.equals(s.mediaId())
                    || (s.background() != null && mediaId.equals(s.background().ref()))
                    || (s.timer() != null && s.timer().signals().stream().anyMatch(sig -> mediaId.equals(sig.soundMediaId()))));
            if (uses) {
                usages.add("modelo de liturgia \"" + template.name() + "\"");
            }
        }
        return usages;
    }

    private LiturgyTemplate find(UUID id) {
        return templates.findById(id)
                .orElseThrow(() -> new PlanningService.NotFoundException("liturgy template " + id + " not found"));
    }

    /** The event of that template and date already exists. */
    static class EventExistsException extends RuntimeException {
        final UUID eventId;

        EventExistsException(UUID eventId) {
            super("the event of this template for this date already exists");
            this.eventId = eventId;
        }
    }
}
