-- The same file may legitimately appear under two kinds (sources sometimes ship one file as both).
ALTER TABLE song_recording DROP CONSTRAINT song_recording_song_id_media_id_key;
ALTER TABLE song_recording ADD CONSTRAINT song_recording_song_kind_media_key UNIQUE (song_id, kind, media_id);
