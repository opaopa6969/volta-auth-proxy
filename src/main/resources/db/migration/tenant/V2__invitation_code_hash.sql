-- Mirror of public V27: store only the SHA-256 hash of invitation codes in the
-- per-tenant invitations table, never the plaintext. See V27 for the rationale
-- and the matching application-side change (SqlStore + SecurityUtils.sha256Hex).
--
-- This runs once per tenant schema (tenant_<slug>). Existing codes are
-- backfilled with pgcrypto SHA-256 (lowercase hex) so issued links keep working.

ALTER TABLE invitations ADD COLUMN code_hash VARCHAR(64);

UPDATE invitations
SET code_hash = encode(public.digest(code, 'sha256'), 'hex')
WHERE code IS NOT NULL;

UPDATE invitations
SET expires_at = now()
WHERE code_hash IS NULL;

UPDATE invitations
SET code_hash = encode(public.digest(id::text, 'sha256'), 'hex')
WHERE code_hash IS NULL;

ALTER TABLE invitations ALTER COLUMN code_hash SET NOT NULL;

ALTER TABLE invitations DROP CONSTRAINT IF EXISTS invitations_code_key;
ALTER TABLE invitations ADD CONSTRAINT invitations_code_hash_key UNIQUE (code_hash);

ALTER TABLE invitations DROP COLUMN code;
