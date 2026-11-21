package webpresenter.server.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A media file in the library. The file itself lives in the media store under {@link #storageKey}. */
@Entity
@Table(name = "media_asset")
class MediaAsset {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaKind kind;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "duration_ms")
    private Long durationMs;

    private Integer width;

    private Integer height;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected MediaAsset() {}

    MediaAsset(UUID id, String title, String originalFilename, MediaKind kind, String contentType, long sizeBytes,
            String sha256, Long durationMs, Integer width, Integer height, String storageKey, Instant createdAt,
            String createdBy) {
        this.id = id;
        this.title = title;
        this.originalFilename = originalFilename;
        this.kind = kind;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.durationMs = durationMs;
        this.width = width;
        this.height = height;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    void rename(String newTitle) {
        this.title = newTitle;
    }

    UUID id() { return id; }
    String title() { return title; }
    String originalFilename() { return originalFilename; }
    MediaKind kind() { return kind; }
    String contentType() { return contentType; }
    long sizeBytes() { return sizeBytes; }
    String sha256() { return sha256; }
    Long durationMs() { return durationMs; }
    Integer width() { return width; }
    Integer height() { return height; }
    String storageKey() { return storageKey; }
    Instant createdAt() { return createdAt; }
    String createdBy() { return createdBy; }
}
