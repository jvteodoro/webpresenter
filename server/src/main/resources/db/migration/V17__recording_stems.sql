-- Separated tracks of a recording (Demucs stems, choir voices): kind STEM with the part it holds,
-- and the recording they were separated from (same timeline).
ALTER TABLE song_recording DROP CONSTRAINT song_recording_kind_check;
ALTER TABLE song_recording ADD CONSTRAINT song_recording_kind_check
    CHECK (kind IN ('SUNG', 'PLAYBACK', 'OTHER', 'STEM'));
ALTER TABLE song_recording ADD COLUMN part varchar(24);
ALTER TABLE song_recording ADD COLUMN derived_from uuid REFERENCES song_recording (id) ON DELETE SET NULL;
ALTER TABLE song_recording ADD CONSTRAINT song_recording_stem_part
    CHECK ((kind = 'STEM') = (part IS NOT NULL));

ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job ADD CONSTRAINT job_type_check
    CHECK (type IN ('YOUTUBE_IMPORT', 'SEPARATE_PLAYBACK', 'TRANSCRIBE_LYRICS', 'SEPARATE_VOICES'));
