CREATE TABLE planned_event (
    id         uuid PRIMARY KEY,
    title      varchar(200) NOT NULL,
    starts_at  timestamptz,
    current    boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL,
    created_by varchar(200) NOT NULL,
    updated_at timestamptz  NOT NULL
);

-- At most one current event (the one players are prepared for).
CREATE UNIQUE INDEX planned_event_single_current_idx ON planned_event (current) WHERE current;

CREATE TABLE planned_event_item (
    id          uuid PRIMARY KEY,
    event_id    uuid        NOT NULL REFERENCES planned_event (id) ON DELETE CASCADE,
    position    integer     NOT NULL,
    type        varchar(16) NOT NULL CHECK (type IN ('MEDIA', 'SLIDE')),
    media_id    uuid,       -- library media (no cross-module FK; deletion is vetoed while referenced)
    slide_title varchar(200),
    slide_body  varchar(4000),
    CHECK ((type = 'MEDIA') = (media_id IS NOT NULL)),
    CHECK ((type = 'SLIDE') = (slide_title IS NOT NULL))
);

CREATE INDEX planned_event_item_event_idx ON planned_event_item (event_id, position);
CREATE INDEX planned_event_item_media_idx ON planned_event_item (media_id);
