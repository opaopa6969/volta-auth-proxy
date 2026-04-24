ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS last_error TEXT;

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_event_id UUID NOT NULL REFERENCES outbox_events(id),
    webhook_id UUID NOT NULL REFERENCES webhook_subscriptions(id),
    event_type VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    status_code INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_outbox ON webhook_deliveries(outbox_event_id);
