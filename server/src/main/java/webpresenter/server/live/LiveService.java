package webpresenter.server.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import webpresenter.protocol.Message;
import webpresenter.server.devices.PlayerEvents;
import webpresenter.server.devices.Players;
import webpresenter.server.library.MediaCatalog;
import webpresenter.server.library.MediaFile;
import webpresenter.server.planning.CurrentEventChanged;
import webpresenter.server.planning.ItemType;
import webpresenter.server.planning.PlannedEvents;

/**
 * The live presentation of the current event. The server is the single source of truth: players
 * only execute commands, and every web client receives the same snapshots. State lives in memory;
 * after a restart the outputs go back to the rest screen.
 */
@Service
class LiveService {

    private static final Logger log = LoggerFactory.getLogger(LiveService.class);

    /** Delay before a command takes effect, so every player starts at the same instant. */
    static final Duration START_LEAD = Duration.ofMillis(300);

    private final PlannedEvents plannedEvents;
    private final MediaCatalog catalog;
    private final Players players;
    private final LiveStreams streams;
    private final Clock clock;

    private final Map<UUID, Reported> playback = new ConcurrentHashMap<>();

    // Guarded by "this".
    private UUID eventId;
    private String title;
    private List<LiveState.Item> items = List.of();
    private int index = -1;
    private LiveState.Status status = LiveState.Status.REST;
    private Instant itemStartedAt;
    private long version;

    private record Reported(Message.PlaybackState state, Instant at) {}

    LiveService(PlannedEvents plannedEvents, MediaCatalog catalog, Players players, LiveStreams streams, Clock clock) {
        this.plannedEvents = plannedEvents;
        this.catalog = catalog;
        this.players = players;
        this.streams = streams;
        this.clock = clock;
        reloadPlan();
    }

    // ------------------------------------------------------------------ commands

    synchronized LiveState play(UUID itemId) {
        requireEvent();
        int target = indexOf(itemId);
        if (target < 0) {
            throw new LiveException("item " + itemId + " is not part of the current event");
        }
        return show(target);
    }

    synchronized LiveState next() {
        requireEvent();
        if (index + 1 < items.size()) {
            return show(index + 1);
        }
        return rest();
    }

    synchronized LiveState previous() {
        requireEvent();
        if (items.isEmpty()) {
            return snapshot();
        }
        return show(Math.max(0, index - 1));
    }

    synchronized LiveState pause() {
        requireEvent();
        if (status == LiveState.Status.PLAYING) {
            status = LiveState.Status.PAUSED;
            players.broadcast(new Message.Pause());
            changed();
        }
        return snapshot();
    }

    synchronized LiveState resume() {
        requireEvent();
        if (status == LiveState.Status.PAUSED) {
            status = LiveState.Status.PLAYING;
            players.broadcast(new Message.Resume());
            changed();
        }
        return snapshot();
    }

    /** Back to the rest screen; "next" continues after the item that was on screen. */
    synchronized LiveState rest() {
        requireEvent();
        status = LiveState.Status.REST;
        itemStartedAt = null;
        players.broadcast(new Message.ShowRest(Message.Transition.DEFAULT));
        changed();
        return snapshot();
    }

    synchronized LiveState snapshot() {
        List<LiveState.Player> playerStates = new ArrayList<>();
        for (Players.PlayerSummary p : players.approved()) {
            Reported r = playback.get(p.id());
            Message.PlaybackState s = r == null || !p.online() ? null : r.state();
            boolean ready = p.online() && p.cacheStatus() != null && eventId != null
                    && eventId.toString().equals(p.cacheStatus().eventId()) && p.cacheStatus().complete();
            playerStates.add(new LiveState.Player(p.id(), p.name(), p.online(), ready,
                    s == null ? null : s.status(), s == null ? null : s.itemId(),
                    s == null ? 0 : estimatedPosition(r), s == null ? 0 : s.durationMs(), s == null ? null : s.detail()));
        }
        if (eventId == null) {
            return LiveState.empty(playerStates, version);
        }
        return new LiveState(eventId, title, items, index, status, itemStartedAt, playerStates, version);
    }

    // ------------------------------------------------------------------ reactions

    /** The current event (or its items) changed: follow it. */
    @TransactionalEventListener(fallbackExecution = true)
    synchronized void onCurrentEventChanged(CurrentEventChanged event) {
        UUID previousEvent = eventId;
        UUID onScreen = index >= 0 && index < items.size() ? items.get(index).id() : null;
        reloadPlan();
        if (!Objects.equals(previousEvent, eventId)) {
            index = -1;
            if (status != LiveState.Status.REST) {
                status = LiveState.Status.REST;
                itemStartedAt = null;
                players.broadcast(new Message.ShowRest(Message.Transition.DEFAULT));
            }
        } else if (onScreen != null) {
            int moved = indexOf(onScreen);
            if (moved >= 0) {
                index = moved;
            } else {
                // The item on screen was removed from the event: leave it.
                index = Math.min(index, items.size() - 1);
                if (status != LiveState.Status.REST) {
                    status = LiveState.Status.REST;
                    itemStartedAt = null;
                    players.broadcast(new Message.ShowRest(Message.Transition.DEFAULT));
                }
            }
        }
        changed();
    }

    /** A player (re)connected: bring it to what the others are showing, joining media in progress. */
    @EventListener
    synchronized void onPlayerReady(PlayerEvents.PlayerReady event) {
        if (eventId == null || status == LiveState.Status.REST || index < 0) {
            return;
        }
        LiveState.Item item = items.get(index);
        long position = item.timeBased() && itemStartedAt != null ? currentPositionMs() : 0;
        players.send(event.deviceId(), new Message.Play(eventId.toString(), item.id().toString(),
                clock.instant().plus(START_LEAD), position, Message.Transition.DEFAULT));
        if (status == LiveState.Status.PAUSED) {
            players.send(event.deviceId(), new Message.Pause());
        }
    }

    @EventListener
    void onPlayerStatusChanged(PlayerEvents.PlayerStatusChanged event) {
        synchronized (this) {
            changed();
        }
    }

    @EventListener
    void onPlayback(PlayerEvents.PlaybackReported event) {
        Message.PlaybackState state = event.state();
        playback.put(event.deviceId(), new Reported(state, clock.instant()));
        synchronized (this) {
            // The first player to finish a video or audio brings the live state back to the rest screen.
            if (state.status() == Message.PlaybackStatus.ENDED && index >= 0 && index < items.size()
                    && items.get(index).id().toString().equals(state.itemId())
                    && (status == LiveState.Status.PLAYING || status == LiveState.Status.PAUSED)) {
                status = LiveState.Status.REST;
                itemStartedAt = null;
                log.info("item {} ended on player {}", state.itemId(), event.deviceId());
            }
            changed();
        }
    }

    // ------------------------------------------------------------------ helpers

    private LiveState show(int target) {
        LiveState.Item item = items.get(target);
        index = target;
        status = item.timeBased() ? LiveState.Status.PLAYING : LiveState.Status.SHOWING;
        Instant startAt = clock.instant().plus(START_LEAD);
        itemStartedAt = startAt;
        players.broadcast(new Message.Play(eventId.toString(), item.id().toString(), startAt, 0,
                Message.Transition.DEFAULT));
        changed();
        log.info("live: item {} '{}' ({}/{})", item.id(), item.title(), target + 1, items.size());
        return snapshot();
    }

    /** Position of the media on screen: the freshest player report if any, else time since start. */
    private long currentPositionMs() {
        String itemId = items.get(index).id().toString();
        return playback.values().stream()
                .filter(r -> itemId.equals(r.state().itemId()) && r.state().status() == Message.PlaybackStatus.PLAYING)
                .max(java.util.Comparator.comparing(Reported::at))
                .map(this::estimatedPosition)
                .orElseGet(() -> Math.max(0, Duration.between(itemStartedAt, clock.instant()).toMillis()));
    }

    private long estimatedPosition(Reported r) {
        if (r.state().status() != Message.PlaybackStatus.PLAYING) {
            return r.state().positionMs();
        }
        return r.state().positionMs() + Duration.between(r.at(), clock.instant()).toMillis();
    }

    private void reloadPlan() {
        var plan = plannedEvents.current().orElse(null);
        if (plan == null) {
            eventId = null;
            title = null;
            items = List.of();
            return;
        }
        eventId = plan.id();
        title = plan.title();
        items = plan.items().stream().map(i -> {
            if (i.type() == ItemType.SLIDE) {
                return new LiveState.Item(i.id(), i.type(), i.slideTitle(), null, null);
            }
            MediaFile media = catalog.find(i.mediaId()).orElse(null);
            return new LiveState.Item(i.id(), i.type(), media == null ? "(mídia removida)" : media.title(),
                    media == null ? null : media.kind(), media == null ? null : media.durationMs());
        }).toList();
    }

    private int indexOf(UUID itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    private void requireEvent() {
        if (eventId == null) {
            throw new LiveException("there is no current event; prepare one in Eventos");
        }
    }

    private void changed() {
        version++;
        streams.publish(snapshot());
    }

    static class LiveException extends RuntimeException {
        LiveException(String message) {
            super(message);
        }
    }
}
