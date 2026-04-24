-- SAAS-008: usage metering
-- Records billable events per tenant. One row per event batch so
-- aggregation is cheap and payload stays bounded.

CREATE TABLE IF NOT EXISTS billing_usage (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    metric     VARCHAR(64) NOT NULL,
    quantity   BIGINT NOT NULL DEFAULT 1,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    meta       JSONB
);

-- Aggregation queries filter on (tenant, metric, window) so this index
-- serves them without a scan. Additional (tenant, recorded_at) index is
-- not needed — the common query shape always scopes by metric too.
CREATE INDEX IF NOT EXISTS idx_billing_usage_tenant_metric_ts
    ON billing_usage(tenant_id, metric, recorded_at DESC);

-- Partition-free design: at scale operators can pg_partman this table
-- later. 1 row per event × a few hundred events per tenant per day is
-- small enough for a single table for years of history.
