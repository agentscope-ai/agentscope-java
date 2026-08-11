-- +migrate Up
-- busy column + session_commands audit table (BYO console capability plan)

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS busy BOOLEAN;

CREATE TABLE IF NOT EXISTS session_commands (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_fk      UUID REFERENCES sessions(id) ON DELETE SET NULL,
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

CREATE UNIQUE INDEX IF NOT EXISTS idx_session_commands_command_id
    ON session_commands(command_id) WHERE command_id IS NOT NULL AND command_id != '';
CREATE INDEX IF NOT EXISTS idx_session_commands_session
    ON session_commands(session_fk, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_commands_time
    ON session_commands(requested_at DESC);
