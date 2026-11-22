package webpresenter.server.planning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import webpresenter.server.playlists.BackgroundSource;

/** Read access to planning for other modules. */
public interface PlannedEvents {

    /** The event players must be prepared for, if any. */
    Optional<EventPlan> current();

    /**
     * An event as players need it: ordered items with media ids or slide text. Scene markers are
     * not items here; each item carries its scene and its effective background music.
     *
     * @param background the event's background music (rest screen); null inherits the platform's
     */
    record EventPlan(UUID id, String title, List<Item> items, BackgroundSource background) {

        public EventPlan(UUID id, String title, List<Item> items) {
            this(id, title, items, null);
        }

        /**
         * @param scene the scene the item belongs to, or null
         * @param background effective background music: the item's, else its scene's, else the
         *     event's; null inherits the platform's
         */
        public record Item(UUID id, ItemType type, UUID mediaId, String slideTitle, String slideBody, UUID songId,
                UUID recordingId, Scene scene, BackgroundSource background, String label, Timer timer) {

            public Item(UUID id, ItemType type, UUID mediaId, String slideTitle, String slideBody, UUID songId,
                    UUID recordingId) {
                this(id, type, mediaId, slideTitle, slideBody, songId, recordingId, null, null, null, null);
            }
        }

        /**
         * A timer's resolved target: a local time (its own, or its scene's end) or a duration from
         * when the item starts; neither when "end of scene" has no end time (it only counts up).
         */
        public record Timer(java.time.LocalTime targetTime, Integer durationSeconds, List<TimerSettings.Signal> signals) {}

        public record Scene(UUID id, String title, java.time.LocalTime starts, java.time.LocalTime ends) {}
    }
}
