ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS processing_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS processing_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_outbox_processing_until ON outbox_events(processing_until) WHERE published_at IS NULL;
