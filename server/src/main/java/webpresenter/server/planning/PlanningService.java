package webpresenter.server.planning;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaUsage;

@Service
class PlanningService implements PlannedEvents, MediaUsage {

    private final PlannedEventRepository repository;
    private final MediaCatalog catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    PlanningService(PlannedEventRepository repository, MediaCatalog catalog, ApplicationEventPublisher events) {
        this.repository = repository;
        this.catalog = catalog;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<PlannedEvent> list() {
        List<PlannedEvent> all = repository.findAllForListing();
        all.forEach(e -> e.items().size()); // item counts are shown in the list
        return all;
    }

    @Transactional(readOnly = true)
    PlannedEvent get(UUID id) {
        return loaded(find(id));
    }

    @Transactional
    PlannedEvent create(String title, Instant startsAt, String user) {
        return repository.save(new PlannedEvent(title.strip(), startsAt, user, clock.instant()));
    }

    @Transactional
    PlannedEvent update(UUID id, String title, Instant startsAt) {
        PlannedEvent event = find(id);
        event.update(title.strip(), startsAt, clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    void delete(UUID id) {
        PlannedEvent event = find(id);
        boolean wasCurrent = event.current();
        repository.delete(event);
        if (wasCurrent) {
            events.publishEvent(new CurrentEventChanged(null));
        }
    }

    @Transactional
    PlannedEvent addMedia(UUID id, UUID mediaId, Integer position) {
        PlannedEvent event = find(id);
        if (catalog.find(mediaId).isEmpty()) {
            throw new InvalidRequestException("media " + mediaId + " does not exist");
        }
        event.addItem(EventItem.media(event, mediaId), position, clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    PlannedEvent addSlide(UUID id, String title, String body, Integer position) {
        PlannedEvent event = find(id);
        event.addItem(EventItem.slide(event, title.strip(), body == null ? "" : body.strip()), position, clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    PlannedEvent editSlide(UUID id, UUID itemId, String title, String body) {
        PlannedEvent event = find(id);
        event.item(itemId).editSlide(title.strip(), body == null ? "" : body.strip());
        event.touch(clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    PlannedEvent removeItem(UUID id, UUID itemId) {
        PlannedEvent event = find(id);
        event.removeItem(itemId, clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    PlannedEvent reorder(UUID id, List<UUID> itemIds) {
        PlannedEvent event = find(id);
        event.reorder(itemIds, clock.instant());
        changed(event);
        return loaded(event);
    }

    /** Makes this the event players prepare for, replacing the previous one. */
    @Transactional
    PlannedEvent makeCurrent(UUID id) {
        PlannedEvent event = find(id);
        if (!event.current()) {
            repository.findByCurrentTrue().ifPresent(previous -> {
                previous.markCurrent(false);
                repository.flush(); // the unique index allows a single current event
            });
            event.markCurrent(true);
        }
        // Published even if it already was current: "prepare again" retries failed downloads.
        events.publishEvent(new CurrentEventChanged(event.id()));
        return loaded(event);
    }

    @Transactional
    void clearCurrent() {
        repository.findByCurrentTrue().ifPresent(event -> {
            event.markCurrent(false);
            events.publishEvent(new CurrentEventChanged(null));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EventPlan> current() {
        return repository.findByCurrentTrue().map(e -> new EventPlan(e.id(), e.title(), e.items().stream()
                .map(i -> new EventPlan.Item(i.id(), i.type(), i.mediaId(), i.slideTitle(), i.slideBody()))
                .toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID mediaId) {
        return repository.findAllUsingMedia(mediaId).stream().map(e -> "evento \"" + e.title() + "\"").toList();
    }

    /** Initializes the lazy item list; views are built after the transaction ends. */
    private static PlannedEvent loaded(PlannedEvent event) {
        event.items().size();
        return event;
    }

    private void changed(PlannedEvent event) {
        if (event.current()) {
            events.publishEvent(new CurrentEventChanged(event.id()));
        }
    }

    private PlannedEvent find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("event " + id + " not found"));
    }

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    static class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }
}
