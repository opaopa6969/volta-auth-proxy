-- AUTH-014 Phase 4 item 4: maintenance_mode flag per tenant.
--
-- Flipped true during the 10s atomic copy window of a cutover. The app's
-- before-handler returns 503 + Retry-After:10 for requests targeting a
-- tenant with maintenance_mode=true, so in-flight writes drain without new
-- ones landing mid-copy.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS maintenance_mode BOOLEAN NOT NULL DEFAULT false;

-- No index needed — the column is checked only inside per-request lookups
-- that already hit tenants by id/slug (primary key / unique index).
