ALTER TABLE users
    ADD COLUMN IF NOT EXISTS locale VARCHAR(10) NOT NULL DEFAULT 'ja';

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS logo_url VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS primary_color VARCHAR(7),
    ADD COLUMN IF NOT EXISTS theme VARCHAR(20) NOT NULL DEFAULT 'default';

CREATE TABLE IF NOT EXISTS user_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(30) NOT NULL,
    provider_sub VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(provider, provider_sub)
);
CREATE INDEX IF NOT EXISTS idx_identities_user ON user_identities(user_id);

CREATE TABLE IF NOT EXISTS user_mfa (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(20) NOT NULL,
    secret TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_idp_configs_tenant_provider ON idp_configs(tenant_id, provider_type);

CREATE TABLE IF NOT EXISTS policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    condition JSONB NOT NULL DEFAULT '{}'::jsonb,
    effect VARCHAR(10) NOT NULL DEFAULT 'allow',
    priority INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS plans (
    id VARCHAR(30) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    max_members INT NOT NULL,
    max_apps INT NOT NULL,
    features TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    plan_id VARCHAR(30) NOT NULL REFERENCES plans(id),
    status VARCHAR(20) NOT NULL,
    stripe_sub_id VARCHAR(255),
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_tenant ON subscriptions(tenant_id);

INSERT INTO plans(id, name, max_members, max_apps, features)
VALUES
    ('free', 'Free', 50, 3, 'basic-auth'),
    ('pro', 'Pro', 500, 20, 'basic-auth,webhook,m2m'),
    ('enterprise', 'Enterprise', 100000, 1000, 'basic-auth,webhook,m2m,scim,saml')
ON CONFLICT(id) DO NOTHING;
