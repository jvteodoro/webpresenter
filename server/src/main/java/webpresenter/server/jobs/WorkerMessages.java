package webpresenter.server.jobs;

import java.util.Map;
import java.util.UUID;

/** JSON messages exchanged with the Python workers (see worker/README.md). */
final class WorkerMessages {

    private WorkerMessages() {}

    /**
     * A job for a worker.
     *
     * @param inputUrl presigned download of the input file, or null
     * @param outputUrl presigned upload (HTTP PUT) for the result file
     * @param outputs jobs with several result files: a presigned upload per name (the worker uploads
     *     the ones it produced), or null
     */
    record JobRequest(UUID jobId, int attempt, JobType type, Map<String, Object> params, String inputUrl,
            String outputUrl, Map<String, String> outputs) {}

    /**
     * Progress or outcome reported by a worker.
     *
     * @param status RUNNING, DONE or FAILED
     * @param filename name of the uploaded result (DONE), used for its type and as a default title
     * @param title title found by the worker (e.g. the video title), or null
     * @param files jobs with several results (DONE): the file name uploaded for each output name
     */
    record WorkerEvent(UUID jobId, int attempt, String status, Integer progress, String message, String filename,
            String title, Map<String, String> files) {

        WorkerEvent(UUID jobId, int attempt, String status, Integer progress, String message, String filename,
                String title) {
            this(jobId, attempt, status, progress, message, filename, title, null);
        }
    }

    /** Broadcast to every worker. */
    record Control(UUID jobId, String action) {}
}
