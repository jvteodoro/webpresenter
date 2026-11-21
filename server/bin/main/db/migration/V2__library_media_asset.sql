CREATE TABLE media_asset (
    id                uuid PRIMARY KEY,
    title             varchar(300) NOT NULL,
    original_filename varchar(500) NOT NULL,
    kind              varchar(16)  NOT NULL CHECK (kind IN ('VIDEO', 'AUDIO', 'IMAGE')),
    content_type      varchar(200) NOT NULL,
    size_bytes        bigint       NOT NULL CHECK (size_bytes >= 0),
    sha256            varchar(64)  NOT NULL,
    duration_ms       bigint,
    width             integer,
    height            integer,
    storage_key       varchar(300) NOT NULL UNIQUE,
    created_at        timestamptz  NOT NULL,
    created_by        varchar(200) NOT NULL
);

CREATE INDEX media_asset_created_at_idx ON media_asset (created_at DESC);
