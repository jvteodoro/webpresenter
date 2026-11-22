package webpresenter.server.live;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import webpresenter.protocol.Message;
import webpresenter.server.library.MediaKind;
import webpresenter.server.planning.ItemType;

/**
 * Snapshot of the live presentation, pushed to every web client.
 *
 * @param currentIndex index of the item on screen (or last on screen when back at the rest screen), -1 if none yet
 * @param currentSlide slide of the song on screen, -1 when the current item is not a song
 * @param version increases with every change, so clients can drop out-of-order snapshots
 */
public record LiveState(UUID eventId, String title, List<Item> items, int currentIndex, int currentSlide,
        Status status, Instant itemStartedAt, List<Player> players, long version) {

    /**
     * A timer's target, computed in local time by each client (and player).
     *
     * @param targetTime "HH:mm:ss" local time, or null
     * @param durationMs from {@code itemStartedAt}, or null; neither: it only counts up
     * @param signalsBeforeEndMs marks at which a sound plays
     */
    public record Timer(String targetTime, Long durationMs, List<Long> signalsBeforeEndMs) {}

    public enum Status {
        /** Rest screen on the outputs. */
        REST,
        /** Video or audio playing. */
        PLAYING,
        PAUSED,
        /** Image or slide on screen. */
        SHOWING
    }

    /**
     * @param slides lyrics slides of a SONG item (empty otherwise)
     * @param hasAudio a SONG item presented with one of its recordings
     * @param timed that recording has cues, so slides advance by themselves
     * @param scene name of the scene the item belongs to, or null
     * @param timer what a TIMER counts down to (null for other items)
     */
    public record Item(UUID id, ItemType type, String title, MediaKind kind, Long durationMs, List<Slide> slides,
            boolean hasAudio, boolean timed, String scene, Timer timer) {

        public Item(UUID id, ItemType type, String title, MediaKind kind, Long durationMs) {
            this(id, type, title, kind, durationMs, List.of(), false, false, null, null);
        }

        public Item(UUID id, ItemType type, String title, MediaKind kind, Long durationMs, List<Slide> slides,
                boolean hasAudio, boolean timed) {
            this(id, type, title, kind, durationMs, slides, hasAudio, timed, null, null);
        }

        Item inScene(String scene) {
            return new Item(id, type, title, kind, durationMs, slides, hasAudio, timed, scene, timer);
        }

        Item withTimer(Timer timer) {
            return new Item(id, type, title, kind, durationMs, slides, hasAudio, timed, scene, timer);
        }

        boolean timeBased() {
            return kind == MediaKind.VIDEO || kind == MediaKind.AUDIO || hasAudio;
        }

        boolean song() {
            return type == ItemType.SONG;
        }
    }

    public record Slide(String label, List<String> lines, String cue, boolean instrumental) {}

    /** What each approved player is doing. */
    public record Player(UUID id, String name, boolean online, boolean ready, Message.PlaybackStatus playback,
            String itemId, long positionMs, long durationMs, String detail) {}

    static LiveState empty(List<Player> players, long version) {
        return new LiveState(null, null, List.of(), -1, -1, Status.REST, null, players, version);
    }
}
