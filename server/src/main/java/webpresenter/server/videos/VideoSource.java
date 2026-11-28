package webpresenter.server.videos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A YouTube channel (or channel page) whose video of each event date is wanted. */
@Entity
@Table(name = "video_source")
class VideoSource {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "channel_url", nullable = false)
    private String channelUrl;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VideoSource() {
    }

    VideoSource(String name, String channelUrl, boolean enabled, String user, Instant now) {
        this.id = UUID.randomUUID();
        this.createdBy = user;
        this.createdAt = now;
        update(name, channelUrl, enabled, now);
    }

    void update(String name, String channelUrl, boolean enabled, Instant now) {
        this.name = name;
        this.channelUrl = channelUrl;
        this.enabled = enabled;
        this.updatedAt = now;
    }

    UUID id() { return id; }
    String name() { return name; }
    String channelUrl() { return channelUrl; }
    boolean enabled() { return enabled; }
    String createdBy() { return createdBy; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
