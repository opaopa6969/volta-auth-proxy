-- Multi-provider OAuth2/OIDC support
-- nonce and code_verifier are not used by GitHub (OAuth2 only)
ALTER TABLE oidc_flows
    ADD COLUMN IF NOT EXISTS provider VARCHAR(32) NOT NULL DEFAULT 'GOOGLE',
    ALTER COLUMN nonce DROP NOT NULL,
    ALTER COLUMN code_verifier DROP NOT NULL;
