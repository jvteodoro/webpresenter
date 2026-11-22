-- Liturgy templates: ordered slots (fixed items or variable "to choose" slots) for weekdays.
CREATE TABLE liturgy_template (
    id          uuid PRIMARY KEY,
    name        varchar(200) NOT NULL,
    weekdays    integer      NOT NULL DEFAULT 0,   -- bit n (1 = Monday … 7 = Sunday, ISO)
    start_time  time,                              -- planned start of the events made from it
    slots       jsonb        NOT NULL,             -- [ItemSpec], see planning.ItemSpec
    created_by  varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

-- Events made from a template, at most one per template and date.
ALTER TABLE planned_event ADD COLUMN template_id uuid REFERENCES liturgy_template (id) ON DELETE SET NULL;
ALTER TABLE planned_event ADD COLUMN template_date date;
CREATE UNIQUE INDEX planned_event_template_date_idx ON planned_event (template_id, template_date)
    WHERE template_id IS NOT NULL;

-- Variable slots in events: a placeholder with a label and the kind of item expected.
ALTER TABLE planned_event_item ADD COLUMN slot_kind varchar(16) CHECK (slot_kind IN ('SONG', 'MEDIA', 'SLIDE', 'ANY'));
ALTER TABLE planned_event_item DROP CONSTRAINT planned_event_item_type_check;
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_type_check
    CHECK (type IN ('MEDIA', 'SLIDE', 'SONG', 'SCENE', 'CLOCK', 'TIMER', 'SLOT'));
ALTER TABLE planned_event_item ADD CONSTRAINT planned_event_item_slot_check
    CHECK ((type = 'SLOT') = (slot_kind IS NOT NULL));
