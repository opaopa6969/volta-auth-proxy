CREATE UNIQUE INDEX IF NOT EXISTS idx_user_mfa_user_type ON user_mfa(user_id, type);
