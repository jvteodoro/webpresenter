-- Musical messages: videos and audio brought by singers, kept as they were provided.
ALTER TABLE media_asset DROP CONSTRAINT media_asset_purpose_check;
ALTER TABLE media_asset ADD CONSTRAINT media_asset_purpose_check CHECK (purpose IN
    ('GENERAL', 'SONG_AUDIO', 'SONG_BACKGROUND', 'BACKGROUND_MUSIC', 'SOUND_EFFECT', 'COVER', 'MISC', 'MUSICAL_MESSAGE'));
