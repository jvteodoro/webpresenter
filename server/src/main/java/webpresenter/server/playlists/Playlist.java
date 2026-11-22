package webpresenter.server.playlists;

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
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "playlist")
class Playlist {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Background music plays the items in random order. */
    @Column(nullable = false)
    private boolean shuffle;

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<PlaylistItem> items = new ArrayList<>();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Playlist() {}

    Playlist(String name, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void describe(String name, String description, boolean shuffle, Instant now) {
        this.name = name;
        this.description = description;
        this.shuffle = shuffle;
        this.updatedAt = now;
    }

    /** Replaces the items, in order. */
    void setItems(List<PlaylistItem.Spec> specs, Instant now) {
        items.clear();
        for (int i = 0; i < specs.size(); i++) {
            items.add(new PlaylistItem(this, i, specs.get(i)));
        }
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String name() { return name; }
    String description() { return description; }
    boolean shuffle() { return shuffle; }
    List<PlaylistItem> items() { return Collections.unmodifiableList(items); }
    String createdBy() { return createdBy; }
    Instant updatedAt() { return updatedAt; }
}
