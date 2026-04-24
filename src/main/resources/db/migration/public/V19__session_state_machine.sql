-- Session State Machine: auth_state, version, scopes, step-up log

-- Sessions: add auth_state and optimistic locking version
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS auth_state VARCHAR(30) NOT NULL DEFAULT 'FULLY_AUTHENTICATED';
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS last_journey_id UUID;

-- Session scopes (step-up authentication)
CREATE TABLE IF NOT EXISTS session_scopes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    scope       VARCHAR(50) NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    granted_by  VARCHAR(20) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_session_scopes_lookup
    ON session_scopes(session_id, scope);

-- Step-up authentication log
CREATE TABLE IF NOT EXISTS step_up_log (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID REFERENCES sessions(id) ON DELETE SET NULL,
    user_id     UUID NOT NULL,
    scope       VARCHAR(50) NOT NULL,
    method      VARCHAR(20) NOT NULL,
    success     BOOLEAN NOT NULL,
    client_ip   VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
