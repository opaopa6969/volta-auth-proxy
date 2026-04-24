CREATE TABLE IF NOT EXISTS user_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(30) NOT NULL,
    provider_sub VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(provider, provider_sub)
);
CREATE INDEX IF NOT EXISTS idx_identities_user ON user_identities(user_id);

INSERT INTO user_identities(user_id, provider, provider_sub)
SELECT id, 'google', google_sub
FROM users
WHERE google_sub IS NOT NULL
ON CONFLICT(provider, provider_sub) DO NOTHING;

ALTER TABLE users
    ALTER COLUMN google_sub DROP NOT NULL;
