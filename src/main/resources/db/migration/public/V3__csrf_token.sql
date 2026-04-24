ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS csrf_token VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_sessions_csrf_token ON sessions(csrf_token);
