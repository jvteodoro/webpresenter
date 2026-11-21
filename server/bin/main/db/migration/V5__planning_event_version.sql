-- Optimistic locking: concurrent edits of the same event (leader and AV technician) must not interleave.
ALTER TABLE planned_event ADD COLUMN version bigint NOT NULL DEFAULT 0;
