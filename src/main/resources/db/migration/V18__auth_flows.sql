-- State Machine: auth_flows + auth_flow_transitions
-- Phase 2: OIDC flow migration

CREATE TABLE auth_flows (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID REFERENCES sessions(id) ON DELETE SET NULL,
    flow_type           VARCHAR(20) NOT NULL,
    flow_version        VARCHAR(10) NOT NULL DEFAULT 'v1',
    current_state       VARCHAR(30) NOT NULL,
    context             JSONB NOT NULL DEFAULT '{}',
    guard_failure_count INT NOT NULL DEFAULT 0,
    version             INT NOT NULL DEFAULT 0,
    journey_id          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    exit_state          VARCHAR(20)
);

CREATE INDEX idx_auth_flows_session ON auth_flows(session_id) WHERE exit_state IS NULL;
CREATE INDEX idx_auth_flows_expires ON auth_flows(expires_at) WHERE exit_state IS NULL;
CREATE INDEX idx_auth_flows_journey ON auth_flows(journey_id);

CREATE TABLE auth_flow_transitions (
    id              BIGSERIAL PRIMARY KEY,
    flow_id         UUID NOT NULL REFERENCES auth_flows(id) ON DELETE CASCADE,
    from_state      VARCHAR(30),
    to_state        VARCHAR(30) NOT NULL,
    trigger         VARCHAR(50) NOT NULL,
    context_snapshot JSONB,
    error_detail    VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_flow_transitions_flow ON auth_flow_transitions(flow_id, created_at);
