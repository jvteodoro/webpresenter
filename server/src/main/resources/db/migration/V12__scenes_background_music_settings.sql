-- System settings (audio: fades, ducking, mixer, platform background music), one JSON per key.
CREATE TABLE app_setting (
    key        varchar(100) PRIMARY KEY,
    value      jsonb        NOT NULL,
    updated_at timestamptz  NOT NULL,
    updated_by varchar(200) NOT NULL
);

-- Background music of an event: a playlist, one track, or explicit silence; null inherits the platform's.
ALTER TABLE planned_event ADD COLUMN background_kind varchar(16)
    CHECK (background_kind IN ('PLAYLIST', 'MEDIA', 'SILENCE'));
ALTER TABLE planned_event ADD COLUMN background_ref uuid;   -- playlist or media (no cross-module FK)
ALTER TABLE planned_event ADD CONSTRAINT planned_event_background_check
    CHECK ((background_kind IN ('PLAYLIST', 'MEDIA')) = (background_ref IS NOT NULL));

-- Scenes: marker items grouping the items after them (until the next scene), with planned times.
ALTER TABLE planned_event_item ADD COLUMN scene_title varchar(200);
ALTER TABLE planned_event_item ADD COLUMN scene_starts time;
ALTER TABLE planned_event_item ADD COLUMN scene_ends time;
-- Background music of an item or a scene (same meaning as on the event; null inherits).
ALTER TABLE planned_event_item ADD COLUMN background_kind varchar(16)
    CHECK (background_kind IN ('PLAYLIST', 'MEDIA', 'SILENCE'));
ALTER TABLE planned_event_item ADD COLUMN background_ref uuid;
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_background_check
    CHECK ((background_kind IN ('PLAYLIST', 'MEDIA')) = (background_ref IS NOT NULL));

ALTER TABLE planned_event_item DROP CONSTRAINT planned_event_item_type_check;
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_type_check
    CHECK (type IN ('MEDIA', 'SLIDE', 'SONG', 'SCENE'));
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_scene_check
    CHECK ((type = 'SCENE') = (scene_title IS NOT NULL));

CREATE INDEX planned_event_item_background_idx ON planned_event_item (background_ref);
CREATE INDEX planned_event_background_idx ON planned_event (background_ref);
