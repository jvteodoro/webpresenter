-- Song recordings and backgrounds live in the library too, but must not bury everyday media.
ALTER TABLE media_asset ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'GENERAL'
    CHECK (purpose IN ('GENERAL', 'SONG_AUDIO', 'SONG_BACKGROUND'));

-- Uploads are de-duplicated by content.
CREATE INDEX media_asset_sha256_idx ON media_asset (sha256);
CREATE INDEX media_asset_purpose_idx ON media_asset (purpose, created_at DESC);
