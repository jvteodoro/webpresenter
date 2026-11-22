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
import webpresenter.server.songs.SongCatalog;

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
    private final SongCatalog songs;
    private final Players players;
    private final LiveStreams streams;
    private final Clock clock;

    private final Map<UUID, Reported> playback = new ConcurrentHashMap<>();

    // Guarded by "this".
    private UUID eventId;
    private String title;
    private List<LiveState.Item> items = List.of();
    private int index = -1;
    /** Slide of the song on screen; -1 when the current item is not a song. */
    private int slide = -1;
    /** Cue times of the current song's recording, for seeking and joining. */
    private Map<UUID, List<Long>> songCues = Map.of();
    private LiveState.Status status = LiveState.Status.REST;
    private Instant itemStartedAt;
    private long version;

    private record Reported(Message.PlaybackState state, Instant at) {}

    LiveService(PlannedEvents plannedEvents, MediaCatalog catalog, SongCatalog songs, Players players,
            LiveStreams streams, Clock clock) {
        this.plannedEvents = plannedEvents;
        this.catalog = catalog;
        this.songs = songs;
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
        return show(target, null);
    }

    /** Inside a song on screen: the next slide. Otherwise (or after its last slide): the next item. */
    synchronized LiveState next() {
        requireEvent();
        if (onSong() && slide + 1 < current().slides().size()) {
            return goToSlide(slide + 1);
        }
        if (index + 1 < items.size()) {
            return show(index + 1, null);
        }
        return rest();
    }

    /** Inside a song on screen: the previous slide. Otherwise: the previous item. */
    synchronized LiveState previous() {
        requireEvent();
        if (items.isEmpty()) {
            return snapshot();
        }
        if (onSongSlide() && slide > 0) {
            return goToSlide(slide - 1);
        }
        return show(Math.max(0, index - 1), null);
    }

    /** Shows a slide of a song; starts the song there if it is not the item on screen. */
    synchronized LiveState slide(UUID itemId, int target) {
        requireEvent();
        int item = indexOf(itemId);
        if (item < 0 || !items.get(item).song()) {
            throw new LiveException("item " + itemId + " is not a song of the current event");
        }
        if (target < 0 || target >= items.get(item).slides().size()) {
            throw new LiveException("slide " + target + " does not exist in this song");
        }
        if (item == index && status != LiveState.Status.REST) {
            return goToSlide(target);
        }
        return show(item, target);
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
        slide = -1;
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
        return new LiveState(eventId, title, items, index, slide, status, itemStartedAt, playerStates, version);
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
            slide = -1;
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
                slide = -1;
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
                clock.instant().plus(START_LEAD), position, Message.Transition.DEFAULT,
                item.song() && slide >= 0 ? slide : null));
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
                slide = -1;
                log.info("item {} ended on player {}", state.itemId(), event.deviceId());
            } else if (state.slide() != null && onSong() && current().id().toString().equals(state.itemId())
                    && state.slide() >= 0 && state.slide() < current().slides().size()) {
                // Timed songs advance on the players; follow them so every web client sees the slide on screen.
                slide = state.slide();
            }
            changed();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Starts an item. A song starts from the beginning ({@code startSlide} null: a timed song shows
     * only its background until the first cue, the intro) or at a given slide, joining its recording
     * at that slide's cue.
     */
    private LiveState show(int target, Integer startSlide) {
        LiveState.Item item = items.get(target);
        index = target;
        slide = !item.song() ? -1 : startSlide != null ? startSlide : item.timed() ? -1 : 0;
        status = item.timeBased() ? LiveState.Status.PLAYING : LiveState.Status.SHOWING;
        long startPosition = item.song() && startSlide != null ? cueOf(item, startSlide) : 0;
        Instant startAt = clock.instant().plus(START_LEAD);
        itemStartedAt = startAt.minusMillis(startPosition);
        players.broadcast(new Message.Play(eventId.toString(), item.id().toString(), startAt, startPosition,
                Message.Transition.DEFAULT, item.song() ? startSlide : null));
        changed();
        log.info("live: item {} '{}' ({}/{})", item.id(), item.title(), target + 1, items.size());
        return snapshot();
    }

    /** Another slide of the song on screen; the players seek its recording to the slide's cue. */
    private LiveState goToSlide(int target) {
        LiveState.Item item = current();
        slide = target;
        Long cue = cues(item).size() > target ? cues(item).get(target) : null;
        if (cue != null) {
            itemStartedAt = clock.instant().minusMillis(cue);
        }
        players.broadcast(new Message.ShowSlide(item.id().toString(), target));
        changed();
        return snapshot();
    }

    private boolean onSongSlide() {
        return onSong() && slide >= 0;
    }

    /** A song is on screen (possibly in its intro, before the first slide). */
    private boolean onSong() {
        return index >= 0 && index < items.size() && items.get(index).song() && status != LiveState.Status.REST;
    }

    private LiveState.Item current() {
        return items.get(index);
    }

    private List<Long> cues(LiveState.Item item) {
        return songCues.getOrDefault(item.id(), List.of());
    }

    /** Start of the slide in the song's recording, or 0. */
    private long cueOf(LiveState.Item item, int target) {
        List<Long> cues = cues(item);
        Long cue = target >= 0 && target < cues.size() ? cues.get(target) : null;
        return cue == null ? 0 : cue;
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
            songCues = Map.of();
            return;
        }
        eventId = plan.id();
        title = plan.title();
        Map<UUID, List<Long>> cues = new java.util.HashMap<>();
        items = plan.items().stream().map(i -> item(i, cues).inScene(i.scene() == null ? null : i.scene().title())).toList();
        songCues = cues;
    }

    private LiveState.Item item(PlannedEvents.EventPlan.Item i, Map<UUID, List<Long>> cues) {
        if (i.type() == ItemType.CLOCK) {
            return new LiveState.Item(i.id(), i.type(), i.label() == null ? "Relógio" : i.label(), null, null);
        }
        if (i.type() == ItemType.TIMER) {
            var t = i.timer();
            return new LiveState.Item(i.id(), i.type(), i.label() == null ? "Cronômetro" : i.label(), null, null)
                    .withTimer(new LiveState.Timer(t.targetTime() == null ? null : t.targetTime().toString(),
                            t.durationSeconds() == null ? null : t.durationSeconds() * 1000L,
                            t.signals().stream().map(sig -> sig.beforeEndSeconds() * 1000L).toList()));
        }
        if (i.type() == ItemType.SLIDE) {
            return new LiveState.Item(i.id(), i.type(), i.slideTitle(), null, null);
        }
        if (i.type() == ItemType.SONG) {
            var song = songs.find(i.songId()).orElse(null);
            if (song == null) {
                return new LiveState.Item(i.id(), i.type(), "(música removida)", null, null);
            }
            var recording = i.recordingId() == null ? null : song.recording(i.recordingId()).orElse(null);
            Long duration = recording == null ? null
                    : catalog.find(recording.mediaId()).map(MediaFile::durationMs).orElse(null);
            boolean timed = recording != null && recording.cueStartMs().stream().anyMatch(Objects::nonNull);
            if (recording != null) {
                cues.put(i.id(), recording.cueStartMs());
            }
            List<LiveState.Slide> slides = song.slides().stream()
                    .map(sl -> new LiveState.Slide(sl.partLabel(), sl.lines(), sl.cue(), sl.instrumental()))
                    .toList();
            return new LiveState.Item(i.id(), i.type(), song.title(), null, duration, slides, recording != null, timed);
        }
        MediaFile media = catalog.find(i.mediaId()).orElse(null);
        return new LiveState.Item(i.id(), i.type(), media == null ? "(mídia removida)" : media.title(),
                media == null ? null : media.kind(), media == null ? null : media.durationMs());
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
