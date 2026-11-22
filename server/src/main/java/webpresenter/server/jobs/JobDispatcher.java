package webpresenter.server.jobs;

import java.util.UUID;

/** Sends work to the workers (RabbitMQ in production; replaced in tests). */
interface JobDispatcher {

    void dispatch(WorkerMessages.JobRequest request);

    void cancel(UUID jobId);
}
