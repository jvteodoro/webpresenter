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
import webpresenter.server.songs.SongCatalog;

@Service
class PlanningService implements PlannedEvents, MediaUsage {

    private final PlannedEventRepository repository;
    private final MediaCatalog catalog;
    private final SongCatalog songs;
    private final ApplicationEventPublisher events;
    private final webpresenter.server.playlists.PlaylistCatalog playlists;
    private final Clock clock = Clock.systemUTC();

    PlanningService(PlannedEventRepository repository, MediaCatalog catalog, SongCatalog songs,
            ApplicationEventPublisher events, webpresenter.server.playlists.PlaylistCatalog playlists) {
        this.playlists = playlists;
        this.repository = repository;
        this.catalog = catalog;
        this.songs = songs;
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

    /** Adds a song; {@code recordingId} null presents the lyrics without audio. */
    @Transactional
    PlannedEvent addSong(UUID id, UUID songId, UUID recordingId, Integer position) {
        PlannedEvent event = find(id);
        requireRecording(songId, recordingId);
        event.addItem(EventItem.song(event, songId, recordingId), position, clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    PlannedEvent chooseRecording(UUID id, UUID itemId, UUID recordingId) {
        PlannedEvent event = find(id);
        EventItem item = event.item(itemId);
        requireRecording(item.songId(), recordingId);
        item.chooseRecording(recordingId);
        event.touch(clock.instant());
        changed(event);
        return loaded(event);
    }

    private void requireRecording(UUID songId, UUID recordingId) {
        var song = songs.find(songId).orElseThrow(() -> new InvalidRequestException("song " + songId + " does not exist"));
        if (recordingId != null && song.recording(recordingId).isEmpty()) {
            throw new InvalidRequestException("recording " + recordingId + " is not a recording of this song");
        }
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
        return repository.findByCurrentTrue().map(PlanningService::plan);
    }

    /** Items without the scene markers, each with its scene and effective background (item → scene → event). */
    static EventPlan plan(PlannedEvent e) {
        List<EventPlan.Item> items = new java.util.ArrayList<>();
        EventItem scene = null;
        for (EventItem i : e.items()) {
            if (i.type() == ItemType.SCENE) {
                scene = i;
                continue;
            }
            var background = i.background() != null ? i.background()
                    : scene != null && scene.background() != null ? scene.background() : e.background();
            items.add(new EventPlan.Item(i.id(), i.type(), i.mediaId(), i.slideTitle(), i.slideBody(), i.songId(),
                    i.recordingId(), scene == null ? null
                            : new EventPlan.Scene(scene.id(), scene.sceneTitle(), scene.sceneStarts(), scene.sceneEnds()),
                    background));
        }
        return new EventPlan(e.id(), e.title(), items, e.background());
    }

    @Transactional
    PlannedEvent addScene(UUID id, String title, java.time.LocalTime starts, java.time.LocalTime ends, Integer position) {
        PlannedEvent event = find(id);
        event.addItem(EventItem.scene(event, title.strip(), starts, ends), position, clock.instant());
        changed(event);
        return loaded(event);
    }

    @Transactional
    PlannedEvent editScene(UUID id, UUID itemId, String title, java.time.LocalTime starts, java.time.LocalTime ends) {
        PlannedEvent event = find(id);
        event.item(itemId).editScene(title.strip(), starts, ends);
        event.touch(clock.instant());
        changed(event);
        return loaded(event);
    }

    /** Background music of an item or scene; null inherits. */
    @Transactional
    PlannedEvent setItemBackground(UUID id, UUID itemId, webpresenter.server.playlists.BackgroundSource background) {
        PlannedEvent event = find(id);
        validate(background);
        event.item(itemId).setBackground(background);
        event.touch(clock.instant());
        changed(event);
        return loaded(event);
    }

    /** Background music of the event; null inherits the platform's. */
    @Transactional
    PlannedEvent setEventBackground(UUID id, webpresenter.server.playlists.BackgroundSource background) {
        PlannedEvent event = find(id);
        validate(background);
        event.setBackground(background, clock.instant());
        changed(event);
        return loaded(event);
    }

    private void validate(webpresenter.server.playlists.BackgroundSource background) {
        if (background == null) {
            return;
        }
        try {
            playlists.validate(background);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID mediaId) {
        var events = new java.util.LinkedHashSet<PlannedEvent>(repository.findAllUsingMedia(mediaId));
        events.addAll(repository.findAllWithBackgroundMedia(mediaId));
        return events.stream().map(e -> "evento \"" + e.title() + "\"").toList();
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
