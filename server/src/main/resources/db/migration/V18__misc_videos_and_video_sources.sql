-- Misc library section: videos downloaded for events (and recurring videos from YouTube channels).
ALTER TABLE media_asset DROP CONSTRAINT media_asset_purpose_check;
ALTER TABLE media_asset ADD CONSTRAINT media_asset_purpose_check CHECK (purpose IN
    ('GENERAL', 'SONG_AUDIO', 'SONG_BACKGROUND', 'BACKGROUND_MUSIC', 'SOUND_EFFECT', 'COVER', 'MISC'));

-- Lyrics read from the frames of a video (on-screen text).
ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job ADD CONSTRAINT job_type_check
    CHECK (type IN ('YOUTUBE_IMPORT', 'SEPARATE_PLAYBACK', 'TRANSCRIBE_LYRICS', 'SEPARATE_VOICES', 'VIDEO_LYRICS'));

-- Recurring videos: a YouTube channel whose video of each event date is downloaded to the misc section.
CREATE TABLE video_source (
    id           uuid PRIMARY KEY,
    name         varchar(120)  NOT NULL,
    channel_url  varchar(500)  NOT NULL,
    enabled      boolean       NOT NULL DEFAULT true,
    created_by   varchar(200)  NOT NULL,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL
);

-- One row per source and event date: the attempt to download that day's video (retried daily until found).
CREATE TABLE video_source_import (
    id              uuid PRIMARY KEY,
    source_id       uuid          NOT NULL REFERENCES video_source (id) ON DELETE CASCADE,
    event_date      date          NOT NULL,
    status          varchar(16)   NOT NULL CHECK (status IN ('QUEUED', 'DONE', 'FAILED')),
    job_id          uuid,
    message         varchar(2000),
    last_attempt_at timestamptz   NOT NULL,
    media_id        uuid,
    UNIQUE (source_id, event_date)
);
