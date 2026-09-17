-- +migrate Up
-- busy column + session_commands audit table (BYO console capability plan)

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS busy BOOLEAN;

CREATE TABLE IF NOT EXISTS session_commands (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_fk      UUID,
    agent_name      TEXT NOT NULL,
    namespace       TEXT NOT NULL DEFAULT 'default',
    session_id      TEXT NOT NULL DEFAULT '',
    command         TEXT NOT NULL,
    operator        TEXT,
    source          TEXT,
    instance_ref    TEXT,
    status          TEXT NOT NULL DEFAULT 'accepted',
    code            TEXT,
    error           TEXT,
    forced          BOOLEAN NOT NULL DEFAULT false,
    command_id      TEXT,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    duration_ms     BIGINT
);
