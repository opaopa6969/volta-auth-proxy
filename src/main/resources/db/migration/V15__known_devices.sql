CREATE TABLE IF NOT EXISTS known_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    fingerprint VARCHAR(128) NOT NULL,
    label VARCHAR(64),
    last_ip TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_known_devices_user ON known_devices(user_id);
