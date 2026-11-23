-- Slide appearance (background image, text colour and size), chosen per song, event and event item;
-- the item's wins over the event's, which wins over the song's.
ALTER TABLE song ADD COLUMN text_color varchar(7) CHECK (text_color ~ '^#[0-9a-fA-F]{6}$');
ALTER TABLE song ADD COLUMN text_scale integer CHECK (text_scale BETWEEN 50 AND 200);
-- {"backgroundMediaId": "…", "textColor": "#ffffff", "textScale": 120}; absent fields inherit.
ALTER TABLE planned_event ADD COLUMN slide_style jsonb;
ALTER TABLE planned_event_item ADD COLUMN slide_style jsonb;
