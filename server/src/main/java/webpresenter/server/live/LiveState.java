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
 * @param version increases with every change, so clients can drop out-of-order snapshots
 */
public record LiveState(UUID eventId, String title, List<Item> items, int currentIndex, Status status,
        Instant itemStartedAt, List<Player> players, long version) {

    public enum Status {
        /** Rest screen on the outputs. */
        REST,
        /** Video or audio playing. */
        PLAYING,
        PAUSED,
        /** Image or slide on screen. */
        SHOWING
    }

    public record Item(UUID id, ItemType type, String title, MediaKind kind, Long durationMs) {

        boolean timeBased() {
            return kind == MediaKind.VIDEO || kind == MediaKind.AUDIO;
        }
    }

    /** What each approved player is doing. */
    public record Player(UUID id, String name, boolean online, boolean ready, Message.PlaybackStatus playback,
            String itemId, long positionMs, long durationMs, String detail) {}

    static LiveState empty(List<Player> players, long version) {
        return new LiveState(null, null, List.of(), -1, Status.REST, null, players, version);
    }
}
