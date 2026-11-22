package webpresenter.server.songs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A hymnal, album or other group of songs. */
@Entity
@Table(name = "song_collection")
class SongCollection {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    /** Grouping for filters, e.g. "Hinários", "CDs Oficiais". */
    @Column(name = "group_name")
    private String groupName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Cover image (library media; no cross-module foreign key). */
    @Column(name = "cover_media_id")
    private UUID coverMediaId;

    private String description;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.IMPORT;

    protected SongCollection() {}

    SongCollection(String name, String slug, String groupName, int sortOrder) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        update(name, groupName, sortOrder);
    }

    void update(String name, String groupName, int sortOrder) {
        this.name = name;
        this.groupName = groupName;
        this.sortOrder = sortOrder;
    }

    UUID id() { return id; }
    String name() { return name; }
    String slug() { return slug; }
    String groupName() { return groupName; }
    UUID coverMediaId() { return coverMediaId; }
    String description() { return description; }
    Source source() { return source; }

    void describe(String name, String description, String groupName, UUID coverMediaId) {
        this.name = name;
        this.description = description;
        this.groupName = groupName;
        this.coverMediaId = coverMediaId;
    }

    void setSource(Source source) {
        this.source = source;
    }
    int sortOrder() { return sortOrder; }
}
