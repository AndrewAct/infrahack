-- Run in the Supabase SQL editor before pointing the app at Postgres.
-- Only the user profile is persisted; reset codes are ephemeral (30s) and stay in-process.

CREATE TABLE IF NOT EXISTS password_reset_users (
    email         TEXT PRIMARY KEY,
    password_hash TEXT        NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
