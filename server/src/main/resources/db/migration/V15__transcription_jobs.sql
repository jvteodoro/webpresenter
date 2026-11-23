-- Lyrics and timing from a sung recording (Whisper, alignment).
ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job ADD CONSTRAINT job_type_check CHECK (type IN ('YOUTUBE_IMPORT', 'SEPARATE_PLAYBACK', 'TRANSCRIBE_LYRICS'));
