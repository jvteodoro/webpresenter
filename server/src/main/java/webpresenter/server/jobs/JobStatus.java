package webpresenter.server.jobs;

enum JobStatus {
    QUEUED, RUNNING, DONE, FAILED, CANCELLED;

    boolean finished() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
