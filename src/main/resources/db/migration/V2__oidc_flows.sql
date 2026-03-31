CREATE TABLE oidc_flows (
    state VARCHAR(128) PRIMARY KEY,
    nonce VARCHAR(128) NOT NULL,
    code_verifier VARCHAR(255) NOT NULL,
    return_to VARCHAR(2048),
    invite_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_oidc_flows_expires_at ON oidc_flows(expires_at);
