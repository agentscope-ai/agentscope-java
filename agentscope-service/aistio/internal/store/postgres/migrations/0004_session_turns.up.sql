-- +migrate Up
-- session_turns: one row per inference turn (user request → response)

CREATE TABLE IF NOT EXISTS session_turns (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_fk          UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_index          INT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'running',  -- running | completed | aborted | failed
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    duration_ms         BIGINT,
    user_preview        TEXT,
    prompt_tokens       BIGINT NOT NULL DEFAULT 0,
    completion_tokens   BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_fk, turn_index)
);

CREATE INDEX IF NOT EXISTS idx_session_turns_session ON session_turns(session_fk, turn_index DESC);
CREATE INDEX IF NOT EXISTS idx_session_turns_running ON session_turns(session_fk) WHERE status = 'running';
