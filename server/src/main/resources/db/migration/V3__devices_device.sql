CREATE TABLE device (
    id             uuid PRIMARY KEY,
    name           varchar(100) NOT NULL,
    hostname       varchar(255) NOT NULL,
    status         varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED')),
    pairing_code   varchar(16),
    token_hash     varchar(64),
    player_version varchar(50),
    displays_json  text         NOT NULL,
    display_index  integer      NOT NULL DEFAULT 0,
    rest_media_id  uuid,        -- library media; cleared when the media is deleted (no cross-module FK)
    created_at     timestamptz  NOT NULL,
    approved_at    timestamptz,
    approved_by    varchar(200),
    last_seen_at   timestamptz  NOT NULL,
    CHECK ((status = 'APPROVED') = (token_hash IS NOT NULL))
);

CREATE INDEX device_rest_media_idx ON device (rest_media_id);
