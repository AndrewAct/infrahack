-- Run in the Supabase SQL editor (or your own Postgres console) before pointing the app at
-- Postgres. This is the control plane: rate_limit_rules is polled by RuleCache and can be edited
-- live - no gateway or backend redeploy needed to change a limit.

CREATE TABLE IF NOT EXISTS rate_limit_rules (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT        NOT NULL UNIQUE,
    dimensions      TEXT[]      NOT NULL,
    tier            TEXT,                    -- NULL = applies to all tiers
    limit_amount    BIGINT      NOT NULL CHECK (limit_amount > 0),
    window_seconds  BIGINT      NOT NULL CHECK (window_seconds > 0),
    burst_capacity  BIGINT      NOT NULL CHECK (burst_capacity >= limit_amount),
    failure_policy  TEXT        CHECK (failure_policy IN ('ALLOW', 'DENY')), -- NULL = inherit platform default
    priority        INT         NOT NULL DEFAULT 0,
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_rules_enabled ON rate_limit_rules (enabled);
