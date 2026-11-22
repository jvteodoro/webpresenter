package webpresenter.server.planning;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** An event (a service): a title, an optional date, and its ordered items. */
@Entity
@Table(name = "planned_event")
class PlannedEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "starts_at")
    private Instant startsAt;

    /** The event players are prepared for (at most one). */
    @Column(nullable = false)
    private boolean current;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<EventItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Every change touches updatedAt, so any edit bumps the version. */
    @Version
    private long version;

    /** Background music of the event; null inherits the platform's. */
    @jakarta.persistence.Embedded
    @jakarta.persistence.AttributeOverride(name = "kind", column = @Column(name = "background_kind"))
    @jakarta.persistence.AttributeOverride(name = "ref", column = @Column(name = "background_ref"))
    private webpresenter.server.playlists.BackgroundSource background;

    /** The liturgy template this event was made from (and for which date), if any. */
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "template_date")
    private java.time.LocalDate templateDate;

    protected PlannedEvent() {}

    PlannedEvent(String title, Instant startsAt, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.startsAt = startsAt;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String title, Instant startsAt, Instant now) {
        this.title = title;
        this.startsAt = startsAt;
        touch(now);
    }

    EventItem addItem(EventItem item, Integer position, Instant now) {
        int index = position == null ? items.size() : Math.clamp(position, 0, items.size());
        items.add(index, item);
        renumber();
        touch(now);
        return item;
    }

    void removeItem(UUID itemId, Instant now) {
        if (!items.removeIf(i -> i.id().equals(itemId))) {
            throw new PlanningService.NotFoundException("item " + itemId + " not found in this event");
        }
        renumber();
        touch(now);
    }

    EventItem item(UUID itemId) {
        return items.stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new PlanningService.NotFoundException("item " + itemId + " not found in this event"));
    }

    /** Reorders the items; the given ids must be exactly the event's items. */
    void reorder(List<UUID> itemIds, Instant now) {
        if (itemIds.size() != items.size() || !new HashSet<>(itemIds).equals(new HashSet<>(items.stream().map(EventItem::id).toList()))) {
            throw new PlanningService.InvalidRequestException("the new order must list every item of the event exactly once");
        }
        items.sort((a, b) -> Integer.compare(itemIds.indexOf(a.id()), itemIds.indexOf(b.id())));
        renumber();
        touch(now);
    }

    void setBackground(webpresenter.server.playlists.BackgroundSource background, Instant now) {
        this.background = background;
        touch(now);
    }

    void fromTemplate(UUID templateId, java.time.LocalDate date) {
        this.templateId = templateId;
        this.templateDate = date;
    }

    /** Puts {@code replacement} where the item {@code itemId} is. */
    void replaceItem(UUID itemId, EventItem replacement, Instant now) {
        int index = items.indexOf(item(itemId));
        items.set(index, replacement);
        renumber();
        touch(now);
    }

    void markCurrent(boolean current) {
        this.current = current;
    }

    long version() {
        return version;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    private void renumber() {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).moveTo(i);
        }
    }

    UUID id() { return id; }
    String title() { return title; }
    Instant startsAt() { return startsAt; }
    boolean current() { return current; }
    List<EventItem> items() { return Collections.unmodifiableList(items); }
    Instant createdAt() { return createdAt; }
    String createdBy() { return createdBy; }
    Instant updatedAt() { return updatedAt; }
    webpresenter.server.playlists.BackgroundSource background() { return background; }
    UUID templateId() { return templateId; }
    java.time.LocalDate templateDate() { return templateDate; }
    long pendingSlots() { return items.stream().filter(i -> i.type() == ItemType.SLOT).count(); }
}
