package webpresenter.server.planning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to planning for other modules. */
public interface PlannedEvents {

    /** The event players must be prepared for, if any. */
    Optional<EventPlan> current();

    /** An event as players need it: ordered items with media ids or slide text. */
    record EventPlan(UUID id, String title, List<Item> items) {

        public record Item(UUID id, ItemType type, UUID mediaId, String slideTitle, String slideBody) {}
    }
}
