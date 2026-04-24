-- AUTH-014 Phase 4 item 4: tenant-owned schema subset.
--
-- TenantSchemaManager runs this migration against `tenant_<slug>` schemas.
-- The schema itself IS the tenant boundary, so `tenant_id` columns are
-- dropped from the in-schema copy — every row here belongs to this tenant.
--
-- FKs that point to platform-shared tables use explicit `public.` so they
-- resolve against the shared schema regardless of the caller's search_path.

-- Tenant-owned membership roster. No `tenant_id` column — schema IS the tenant.
CREATE TABLE memberships (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES public.users(id),
    role       VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    invited_by UUID REFERENCES public.users(id),
    is_active  BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (user_id)
);
CREATE INDEX idx_membership_user ON memberships(user_id);

CREATE TABLE sessions (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES public.users(id),
    return_to       VARCHAR(2048),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    invalidated_at  TIMESTAMPTZ,
    ip_address      INET,
    user_agent      TEXT,
    mfa_verified    BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_sessions_user ON sessions(user_id);

CREATE TABLE invitations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL UNIQUE,
    email       VARCHAR(255),
    role        VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    max_uses    INT NOT NULL DEFAULT 1,
    used_count  INT NOT NULL DEFAULT 0,
    created_by  UUID NOT NULL REFERENCES public.users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE invitation_usages (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invitation_id UUID NOT NULL REFERENCES invitations(id),
    used_by       UUID NOT NULL REFERENCES public.users(id),
    used_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    timestamp   TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type  VARCHAR(50) NOT NULL,
    actor_id    UUID,
    actor_ip    INET,
    target_type VARCHAR(30),
    target_id   VARCHAR(255),
    detail      JSONB,
    request_id  UUID NOT NULL
);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_actor ON audit_logs(actor_id);

CREATE TABLE webhook_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_url    TEXT NOT NULL,
    secret          VARCHAR(255) NOT NULL,
    events          TEXT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ
);

CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type   VARCHAR(80) NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    delivery_attempts INT NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    last_error        TEXT,
    claim_token       VARCHAR(64),
    claim_expires_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events(next_attempt_at) WHERE published_at IS NULL;

CREATE TABLE webhook_deliveries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_event_id UUID NOT NULL REFERENCES outbox_events(id),
    webhook_id     UUID NOT NULL REFERENCES webhook_subscriptions(id),
    event_type     VARCHAR(80) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    status_code    INT,
    response_body  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_deliveries_created ON webhook_deliveries(created_at DESC);

CREATE TABLE known_devices (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES public.users(id),
    fingerprint    VARCHAR(128) NOT NULL,
    label          VARCHAR(64),
    last_ip        TEXT,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, fingerprint)
);

-- In the shared schema this was `tenant_security_policies` keyed by tenant_id.
-- Per-schema this is a singleton table (0 or 1 row) so `id=1` is the only PK.
CREATE TABLE security_policies (
    id                     SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    new_device_action      VARCHAR(20) NOT NULL DEFAULT 'notify',
    risk_action_threshold  INT NOT NULL DEFAULT 4,
    risk_block_threshold   INT NOT NULL DEFAULT 5,
    notify_user            BOOLEAN NOT NULL DEFAULT true,
    notify_admin           BOOLEAN NOT NULL DEFAULT false,
    auto_trust_passkey     BOOLEAN NOT NULL DEFAULT true,
    max_trusted_devices    INT NOT NULL DEFAULT 10,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID REFERENCES public.users(id)
);

CREATE TABLE billing_usage (
    id          BIGSERIAL PRIMARY KEY,
    metric      VARCHAR(64) NOT NULL,
    quantity    BIGINT NOT NULL DEFAULT 1,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    meta        JSONB
);
CREATE INDEX idx_billing_usage_metric_ts ON billing_usage(metric, recorded_at DESC);

CREATE TABLE subscriptions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id       VARCHAR(30) NOT NULL REFERENCES public.plans(id),
    status        VARCHAR(20) NOT NULL,
    stripe_sub_id VARCHAR(255),
    started_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ
);
