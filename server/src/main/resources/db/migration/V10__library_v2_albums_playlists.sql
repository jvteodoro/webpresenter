-- Albums: the song collections gain a cover, a description and their origin.
ALTER TABLE song_collection ADD COLUMN cover_media_id uuid;   -- library image (no cross-module FK)
ALTER TABLE song_collection ADD COLUMN description varchar(2000);
ALTER TABLE song_collection ADD COLUMN source varchar(16) NOT NULL DEFAULT 'IMPORT'
    CHECK (source IN ('MANUAL', 'IMPORT', 'PIPELINE'));
CREATE INDEX song_collection_cover_idx ON song_collection (cover_media_id);

-- New library purposes.
ALTER TABLE media_asset DROP CONSTRAINT media_asset_purpose_check;
ALTER TABLE media_asset ADD CONSTRAINT media_asset_purpose_check CHECK (purpose IN
    ('GENERAL', 'SONG_AUDIO', 'SONG_BACKGROUND', 'BACKGROUND_MUSIC', 'SOUND_EFFECT', 'COVER'));

-- Playlists: ordered songs and library media, for events and background music.
CREATE TABLE playlist (
    id          uuid PRIMARY KEY,
    name        varchar(200) NOT NULL,
    description varchar(2000),
    shuffle     boolean      NOT NULL DEFAULT false,
    created_by  varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE TABLE playlist_item (
    id           uuid PRIMARY KEY,
    playlist_id  uuid        NOT NULL REFERENCES playlist (id) ON DELETE CASCADE,
    position     integer     NOT NULL,
    type         varchar(16) NOT NULL CHECK (type IN ('SONG', 'MEDIA')),
    song_id      uuid,       -- songs module (no cross-module FK)
    recording_id uuid,       -- recording of that song to play (null: sung, else first)
    media_id     uuid,       -- library media (no cross-module FK; deletion vetoed while used)
    CHECK ((type = 'SONG') = (song_id IS NOT NULL)),
    CHECK ((type = 'MEDIA') = (media_id IS NOT NULL))
);

CREATE INDEX playlist_item_playlist_idx ON playlist_item (playlist_id, position);
CREATE INDEX playlist_item_song_idx ON playlist_item (song_id);
CREATE INDEX playlist_item_media_idx ON playlist_item (media_id);
