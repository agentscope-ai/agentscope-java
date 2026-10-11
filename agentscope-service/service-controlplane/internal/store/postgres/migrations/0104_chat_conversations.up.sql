-- +migrate Up

CREATE TABLE IF NOT EXISTS chat_conversations (
    id                  UUID PRIMARY KEY,
    tenant              TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    creator_ref         TEXT NOT NULL,
    agent_id            UUID NOT NULL,
    agent_name          TEXT NOT NULL,
    session_fk          UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    runtime_session_id  TEXT NOT NULL,
    title               TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'active',
    pinned              BOOLEAN NOT NULL DEFAULT false,
    last_read_seq       INT NOT NULL DEFAULT 0,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chat_conversations_status_check CHECK (status IN ('active', 'archived')),
    CONSTRAINT chat_conversations_session_unique UNIQUE (session_fk)
);

CREATE INDEX IF NOT EXISTS chat_conversations_owner_updated_idx
    ON chat_conversations (tenant, namespace, creator_ref, status, pinned DESC, updated_at DESC);

CREATE INDEX IF NOT EXISTS chat_conversations_agent_idx
    ON chat_conversations (tenant, namespace, agent_id, updated_at DESC);
