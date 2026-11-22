-- Processing jobs (YouTube import, Demucs separation, later transcription …) run by workers.
CREATE TABLE job (
    id          uuid PRIMARY KEY,
    type        varchar(32)   NOT NULL CHECK (type IN ('YOUTUBE_IMPORT', 'SEPARATE_PLAYBACK')),
    status      varchar(16)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    title       varchar(300)  NOT NULL,          -- what people see in the queue
    params      jsonb         NOT NULL,
    result      jsonb,
    progress    integer       NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    message     varchar(2000),
    attempt     integer       NOT NULL DEFAULT 1,
    created_by  varchar(200)  NOT NULL,
    created_at  timestamptz   NOT NULL,
    started_at  timestamptz,
    finished_at timestamptz,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL DEFAULT 0
);

CREATE INDEX job_created_idx ON job (created_at DESC);
CREATE INDEX job_status_idx ON job (status);
