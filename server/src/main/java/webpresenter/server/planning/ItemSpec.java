package webpresenter.server.planning;

import java.time.LocalTime;
import java.util.UUID;
import webpresenter.server.playlists.BackgroundSource;

/**
 * An item described by value: the slots of a liturgy template (stored as JSON) and what fills a
 * variable slot of an event. Only the fields of its type are used.
 *
 * @param label clock/timer text, or a variable slot's name ("Hino escola sabatina 1")
 * @param slotKind for SLOT: the kind of item expected
 */
record ItemSpec(ItemType type, UUID mediaId, String slideTitle, String slideBody, UUID songId, UUID recordingId,
        String sceneTitle, LocalTime sceneStarts, LocalTime sceneEnds, String label, TimerSettings timer,
        BackgroundSource background, SlotKind slotKind) {

    /** What a variable slot accepts. */
    enum SlotKind { SONG, MEDIA, SLIDE, ANY }

    boolean fits(SlotKind kind) {
        return switch (kind) {
            case ANY -> type == ItemType.SONG || type == ItemType.MEDIA || type == ItemType.SLIDE;
            case SONG -> type == ItemType.SONG;
            case MEDIA -> type == ItemType.MEDIA;
            case SLIDE -> type == ItemType.SLIDE;
        };
    }
}
