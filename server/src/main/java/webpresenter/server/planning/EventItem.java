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

    /** Song for SONG items (no cross-module foreign key). */
    @Column(name = "song_id")
    private UUID songId;

    /** Recording of the song to play, or null to present the lyrics without audio. */
    @Column(name = "recording_id")
    private UUID recordingId;

    @Column(name = "slide_body")
    private String slideBody;

    @Column(name = "scene_title")
    private String sceneTitle;

    @Column(name = "scene_starts")
    private java.time.LocalTime sceneStarts;

    @Column(name = "scene_ends")
    private java.time.LocalTime sceneEnds;

    /** Background music of this item (or of the scene, on a scene marker); null inherits. */
    @jakarta.persistence.Embedded
    @jakarta.persistence.AttributeOverride(name = "kind", column = @Column(name = "background_kind"))
    @jakarta.persistence.AttributeOverride(name = "ref", column = @Column(name = "background_ref"))
    private webpresenter.server.playlists.BackgroundSource background;

    protected EventItem() {}

    static EventItem scene(PlannedEvent event, String title, java.time.LocalTime starts, java.time.LocalTime ends) {
        EventItem item = new EventItem(event, ItemType.SCENE);
        item.editScene(title, starts, ends);
        return item;
    }

    void editScene(String title, java.time.LocalTime starts, java.time.LocalTime ends) {
        if (type != ItemType.SCENE) {
            throw new PlanningService.InvalidRequestException("only scenes have a name and times");
        }
        if (starts != null && ends != null && !ends.isAfter(starts)) {
            throw new PlanningService.InvalidRequestException("a scene must end after it starts");
        }
        this.sceneTitle = title;
        this.sceneStarts = starts;
        this.sceneEnds = ends;
    }

    void setBackground(webpresenter.server.playlists.BackgroundSource background) {
        this.background = background;
    }

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

    static EventItem song(PlannedEvent event, UUID songId, UUID recordingId) {
        EventItem item = new EventItem(event, ItemType.SONG);
        item.songId = songId;
        item.recordingId = recordingId;
        return item;
    }

    void chooseRecording(UUID recordingId) {
        if (type != ItemType.SONG) {
            throw new PlanningService.InvalidRequestException("only songs have recordings");
        }
        this.recordingId = recordingId;
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
    UUID songId() { return songId; }
    UUID recordingId() { return recordingId; }
    String sceneTitle() { return sceneTitle; }
    java.time.LocalTime sceneStarts() { return sceneStarts; }
    java.time.LocalTime sceneEnds() { return sceneEnds; }
    webpresenter.server.playlists.BackgroundSource background() { return background; }
}
