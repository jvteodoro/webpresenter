CREATE TABLE song (
    id                  uuid PRIMARY KEY,
    title               varchar(300) NOT NULL,
    search_text         text         NOT NULL,
    alternative_titles  text,
    language            varchar(16)  NOT NULL,
    authors             varchar(500),
    copyright           varchar(500),
    background_media_id uuid,        -- library media (no cross-module FK; deletion vetoed while used)
    source              varchar(16)  NOT NULL CHECK (source IN ('MANUAL', 'IMPORT', 'PIPELINE')),
    origin              varchar(50),
    external_id         varchar(100),
    status              varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'READY')),
    lyrics              jsonb        NOT NULL,
    lyrics_version      integer      NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    UNIQUE (origin, external_id)
);

CREATE INDEX song_title_idx ON song (title);
CREATE INDEX song_background_idx ON song (background_media_id);

CREATE TABLE song_recording (
    id       uuid PRIMARY KEY,
    song_id  uuid        NOT NULL REFERENCES song (id) ON DELETE CASCADE,
    kind     varchar(16) NOT NULL CHECK (kind IN ('SUNG', 'PLAYBACK', 'OTHER')),
    media_id uuid        NOT NULL,
    source   varchar(16) NOT NULL CHECK (source IN ('MANUAL', 'IMPORT', 'PIPELINE')),
    timing   jsonb,
    UNIQUE (song_id, media_id)
);

CREATE INDEX song_recording_media_idx ON song_recording (media_id);

CREATE TABLE song_collection (
    id         uuid PRIMARY KEY,
    name       varchar(200) NOT NULL,
    slug       varchar(100) NOT NULL UNIQUE,
    group_name varchar(100),
    sort_order integer      NOT NULL DEFAULT 0
);

CREATE TABLE song_collection_entry (
    id            uuid PRIMARY KEY,
    song_id       uuid NOT NULL REFERENCES song (id) ON DELETE CASCADE,
    collection_id uuid NOT NULL REFERENCES song_collection (id) ON DELETE CASCADE,
    number        varchar(20),
    UNIQUE (song_id, collection_id)
);

CREATE INDEX song_collection_entry_collection_idx ON song_collection_entry (collection_id);
