-- Songs as event items: the song and the chosen recording (none = the operator advances slides).
ALTER TABLE planned_event_item ADD COLUMN song_id uuid;       -- songs module (no cross-module FK)
ALTER TABLE planned_event_item ADD COLUMN recording_id uuid;  -- recording of that song, or null

ALTER TABLE planned_event_item DROP CONSTRAINT planned_event_item_type_check;
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_type_check CHECK (type IN ('MEDIA', 'SLIDE', 'SONG'));
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_song_check CHECK ((type = 'SONG') = (song_id IS NOT NULL));
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_recording_check CHECK (recording_id IS NULL OR type = 'SONG');

CREATE INDEX planned_event_item_song_idx ON planned_event_item (song_id);
