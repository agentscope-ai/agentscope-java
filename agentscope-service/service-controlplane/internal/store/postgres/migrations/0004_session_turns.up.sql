-- +migrate Up
-- session_turns: one row per inference turn (user request → response)

CREATE TABLE IF NOT EXISTS session_turns (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_fk          UUID NOT NULL,
    turn_index          INT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'running',  -- running | completed | aborted | failed
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    duration_ms         BIGINT,
    user_preview        TEXT,
    prompt_tokens       BIGINT NOT NULL DEFAULT 0,
    completion_tokens   BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
