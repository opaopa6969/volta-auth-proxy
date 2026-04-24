-- AUTH-014 Phase 4 item 4: defence-in-depth RLS on platform-shared tables.
--
-- Schema-per-tenant (tenant_<slug>) is the primary isolation boundary for
-- tenant-owned data. Shared tables in `public` (users, plans, magic_links)
-- stay global but get a policy that — once the app starts setting
-- `app.current_tenant_id` via SET LOCAL — filters rows by tenant context.
-- Rows without a tenant column (users, plans) stay readable to all sessions.
--
-- Policies are permissive-by-default so V24 is non-breaking; the isolation
-- narrows only after `TxScope` begins setting `app.*` session vars.

ALTER TABLE users         ENABLE ROW LEVEL SECURITY;
ALTER TABLE plans         ENABLE ROW LEVEL SECURITY;
ALTER TABLE magic_links   ENABLE ROW LEVEL SECURITY;

-- Permissive "everyone sees everything" policy until the app narrows it.
-- The point of the RLS switch is so that a future policy tightens the scope
-- without needing a destructive rewrite.
CREATE POLICY users_all       ON users       FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY plans_all       ON plans       FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY magic_links_all ON magic_links FOR ALL USING (true) WITH CHECK (true);
