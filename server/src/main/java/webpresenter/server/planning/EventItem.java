package webpresenter.server.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** One step of an event: a library file or a text slide. */
@Entity
@Table(name = "planned_event_item")
class EventItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private PlannedEvent event;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemType type;

    /** Library media for MEDIA items (no cross-module foreign key; deletion is vetoed while in use). */
    @Column(name = "media_id")
    private UUID mediaId;

    @Column(name = "slide_title")
    private String slideTitle;

    @Column(name = "slide_body")
    private String slideBody;

    protected EventItem() {}

    static EventItem media(PlannedEvent event, UUID mediaId) {
        EventItem item = new EventItem(event, ItemType.MEDIA);
        item.mediaId = mediaId;
        return item;
    }

    static EventItem slide(PlannedEvent event, String title, String body) {
        EventItem item = new EventItem(event, ItemType.SLIDE);
        item.slideTitle = title;
        item.slideBody = body;
        return item;
    }

    private EventItem(PlannedEvent event, ItemType type) {
        this.id = UUID.randomUUID();
        this.event = event;
        this.type = type;
    }

    void editSlide(String title, String body) {
        if (type != ItemType.SLIDE) {
            throw new PlanningService.InvalidRequestException("only text slides can be edited");
        }
        this.slideTitle = title;
        this.slideBody = body;
    }

    void moveTo(int position) {
        this.position = position;
    }

    UUID id() { return id; }
    int position() { return position; }
    ItemType type() { return type; }
    UUID mediaId() { return mediaId; }
    String slideTitle() { return slideTitle; }
    String slideBody() { return slideBody; }
}
