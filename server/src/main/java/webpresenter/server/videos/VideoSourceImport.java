package webpresenter.server.videos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The attempt to download a source's video of one event date. */
@Entity
@Table(name = "video_source_import")
class VideoSourceImport {

    enum Status { QUEUED, DONE, FAILED }

    @Id
    private UUID id;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "job_id")
    private UUID jobId;

    private String message;

    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    @Column(name = "media_id")
    private UUID mediaId;

    protected VideoSourceImport() {
    }

    VideoSourceImport(UUID sourceId, LocalDate eventDate) {
        this.id = UUID.randomUUID();
        this.sourceId = sourceId;
        this.eventDate = eventDate;
    }

    void queued(UUID job, Instant now) {
        this.status = Status.QUEUED;
        this.jobId = job;
        this.lastAttemptAt = now;
        this.message = null;
    }

    void done(UUID media) {
        this.status = Status.DONE;
        this.mediaId = media;
        this.message = null;
    }

    void failed(String reason) {
        this.status = Status.FAILED;
        this.message = reason;
    }

    UUID id() { return id; }
    UUID sourceId() { return sourceId; }
    LocalDate eventDate() { return eventDate; }
    Status status() { return status; }
    UUID jobId() { return jobId; }
    String message() { return message; }
    Instant lastAttemptAt() { return lastAttemptAt; }
    UUID mediaId() { return mediaId; }
}
