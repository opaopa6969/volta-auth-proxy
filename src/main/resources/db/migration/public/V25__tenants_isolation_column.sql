-- AUTH-014 Phase 4 item 4: per-tenant isolation mode column.
--
-- 'shared'   = tenant-owned data still lives in public.* (current default)
-- 'schema'   = tenant_<slug> schema owns the data
-- 'database' = reserved for future per-tenant database (not yet implemented)
--
-- During the phased cutover an operator flips each tenant from 'shared' to
-- 'schema' one at a time. The app reads this column in the request
-- before-handler to decide which schema to SET LOCAL search_path to.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS isolation VARCHAR(16) NOT NULL DEFAULT 'shared';

ALTER TABLE tenants
    ADD CONSTRAINT tenants_isolation_chk
    CHECK (isolation IN ('shared', 'schema', 'database'));

CREATE INDEX IF NOT EXISTS idx_tenants_isolation ON tenants(isolation)
    WHERE isolation <> 'shared';
