-- Invitation tokens were stored in plaintext (invitations.code), so a DB breach
-- would let an attacker replay any unused invite link. We now store only a
-- SHA-256 hash of the code; the raw code is shown once at issue time (email /
-- API response) and never persisted.
--
-- Migration strategy (backward compatible):
--   1. Add invitations.code_hash.
--   2. Backfill it from the existing plaintext code using pgcrypto's SHA-256
--      (lowercase hex), which matches SecurityUtils.sha256Hex on the app side.
--      This keeps already-issued invite links valid after deploy.
--   3. Move the UNIQUE constraint to code_hash and drop the plaintext column,
--      removing all plaintext invite codes from the database.
--
-- Note: any invite whose plaintext code is somehow NULL/blank cannot be
-- recovered and is effectively expired (no matching hash will ever be presented).

ALTER TABLE invitations ADD COLUMN code_hash VARCHAR(64);

UPDATE invitations
SET code_hash = encode(digest(code, 'sha256'), 'hex')
WHERE code IS NOT NULL;

-- Expire any rows we could not backfill (defensive; should be none in practice).
UPDATE invitations
SET expires_at = now()
WHERE code_hash IS NULL;

-- Give un-backfillable rows a placeholder hash so the NOT NULL/UNIQUE constraints
-- below can be applied. These are already expired above and use the row id as a
-- guaranteed-unique, non-guessable value.
UPDATE invitations
SET code_hash = encode(digest(id::text, 'sha256'), 'hex')
WHERE code_hash IS NULL;

ALTER TABLE invitations ALTER COLUMN code_hash SET NOT NULL;

-- Replace the old plaintext-code unique constraint with one on the hash.
ALTER TABLE invitations DROP CONSTRAINT IF EXISTS invitations_code_key;
ALTER TABLE invitations ADD CONSTRAINT invitations_code_hash_key UNIQUE (code_hash);

-- Drop the plaintext column: invite codes are no longer recoverable from the DB.
ALTER TABLE invitations DROP COLUMN code;
