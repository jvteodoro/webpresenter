package webpresenter.server.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "job")
class Job {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String params;

    @JdbcTypeCode(SqlTypes.JSON)
    private String result;

    @Column(nullable = false)
    private int progress;

    private String message;

    /** Incremented on retry; worker events of older attempts are ignored. */
    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Job() {}

    Job(JobType type, String title, String params, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.status = JobStatus.QUEUED;
        this.title = title;
        this.params = params;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void running(int progress, String message, Instant now) {
        if (status == JobStatus.QUEUED) {
            startedAt = now;
        }
        status = JobStatus.RUNNING;
        this.progress = Math.clamp(progress, 0, 100);
        this.message = truncate(message);
        updatedAt = now;
    }

    void done(String result, String message, Instant now) {
        status = JobStatus.DONE;
        progress = 100;
        this.result = result;
        this.message = truncate(message);
        finishedAt = now;
        updatedAt = now;
    }

    void failed(String message, Instant now) {
        status = JobStatus.FAILED;
        this.message = truncate(message);
        finishedAt = now;
        updatedAt = now;
    }

    void cancelled(Instant now) {
        status = JobStatus.CANCELLED;
        message = "Cancelado";
        finishedAt = now;
        updatedAt = now;
    }

    void retry(Instant now) {
        attempt++;
        status = JobStatus.QUEUED;
        progress = 0;
        message = null;
        result = null;
        startedAt = null;
        finishedAt = null;
        updatedAt = now;
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 2000 ? message : message.substring(0, 1997) + "…";
    }

    UUID id() { return id; }
    JobType type() { return type; }
    JobStatus status() { return status; }
    String title() { return title; }
    String params() { return params; }
    String result() { return result; }
    int progress() { return progress; }
    String message() { return message; }
    int attempt() { return attempt; }
    String createdBy() { return createdBy; }
    Instant createdAt() { return createdAt; }
    Instant startedAt() { return startedAt; }
    Instant finishedAt() { return finishedAt; }
    Instant updatedAt() { return updatedAt; }
}
