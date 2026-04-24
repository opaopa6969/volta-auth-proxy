-- Phase 1: Conditional Access + Device Trust + GDPR
-- Source: dge/specs/conditional-access-and-privacy.md

-- Trusted devices (device trust via Persistent Cookie)
CREATE TABLE trusted_devices (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id    UUID NOT NULL,
    device_name  VARCHAR(100),
    user_agent   VARCHAR(500),
    ip_address   VARCHAR(45),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_trusted_devices_user_device
    ON trusted_devices(user_id, device_id);
CREATE INDEX idx_trusted_devices_user
    ON trusted_devices(user_id);

-- Tenant security policies (conditional access settings)
CREATE TABLE tenant_security_policies (
    tenant_id              UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    new_device_action      VARCHAR(20) NOT NULL DEFAULT 'notify',
    risk_action_threshold  INT NOT NULL DEFAULT 4,
    risk_block_threshold   INT NOT NULL DEFAULT 5,
    notify_user            BOOLEAN NOT NULL DEFAULT true,
    notify_admin           BOOLEAN NOT NULL DEFAULT false,
    auto_trust_passkey     BOOLEAN NOT NULL DEFAULT true,
    max_trusted_devices    INT NOT NULL DEFAULT 10,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID REFERENCES users(id)
);

-- GDPR: soft delete support
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NOT NULL;

-- GDPR: audit_logs FK change (CASCADE → SET NULL for user deletion)
-- Note: only run if the constraint exists; safe to re-run
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_name = 'audit_logs_actor_id_fkey'
               AND table_name = 'audit_logs') THEN
        ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_actor_id_fkey;
        ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_actor_id_fkey
            FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;
