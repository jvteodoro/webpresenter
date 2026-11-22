package webpresenter.server.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A job as shown to people.
 *
 * @param result ids of what the job created ({@code mediaId}, {@code songId}, {@code recordingId}), or null
 */
record JobView(UUID id, JobType type, JobStatus status, String title, Map<String, Object> params,
        Map<String, Object> result, int progress, String message, int attempt, String createdBy, Instant createdAt,
        Instant startedAt, Instant finishedAt, Instant updatedAt) {}
