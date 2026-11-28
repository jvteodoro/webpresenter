package webpresenter.server.jobs;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Downloads that other modules ask for (recurring videos); the queue and its results stay in this module. */
public interface ImportJobs {

    /**
     * Queues the video of a channel for a day into the misc section. The worker finds it by its title
     * (the day written in it) or, failing that, by its upload date.
     *
     * @param title the library title of the video
     * @return the job's id
     */
    UUID startChannelVideo(String channelUrl, LocalDate day, String title, String user);

    /** The outcome of a job, or empty when there is no such job. */
    Optional<Outcome> outcome(UUID jobId);

    /**
     * @param status the job's status: QUEUED, RUNNING, DONE, FAILED or CANCELLED
     * @param message what the queue says (why it failed, when it did)
     * @param mediaId the library item a finished import created, or null
     */
    record Outcome(String status, String message, UUID mediaId) {}
}
