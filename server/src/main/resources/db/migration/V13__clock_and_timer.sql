-- Clock and timer items.
ALTER TABLE planned_event_item ADD COLUMN label varchar(200);          -- shown above the clock/timer
ALTER TABLE planned_event_item ADD COLUMN timer_mode varchar(16)
    CHECK (timer_mode IN ('SCENE_END', 'TIME', 'DURATION'));
ALTER TABLE planned_event_item ADD COLUMN timer_time time;              -- TIME: counts down to this local time
ALTER TABLE planned_event_item ADD COLUMN timer_duration_seconds integer -- DURATION: from when the item starts
    CHECK (timer_duration_seconds > 0);
-- Signals: [{"beforeEndSeconds": 300, "soundMediaId": "…"}] (library audio; no cross-module FK).
ALTER TABLE planned_event_item ADD COLUMN timer_signals jsonb;

ALTER TABLE planned_event_item DROP CONSTRAINT planned_event_item_type_check;
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_type_check
    CHECK (type IN ('MEDIA', 'SLIDE', 'SONG', 'SCENE', 'CLOCK', 'TIMER'));
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_timer_check
    CHECK ((type = 'TIMER') = (timer_mode IS NOT NULL));
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_timer_time_check
    CHECK (timer_mode IS DISTINCT FROM 'TIME' OR timer_time IS NOT NULL);
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_timer_duration_check
    CHECK (timer_mode IS DISTINCT FROM 'DURATION' OR timer_duration_seconds IS NOT NULL);

-- A clock over the rest screen (on its image or video, or on black).
ALTER TABLE device ADD COLUMN rest_clock boolean NOT NULL DEFAULT false;
