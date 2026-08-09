-- +migrate Up
-- Hosted subagent background task records (TaskRepository / dp_tasks).

CREATE TABLE IF NOT EXISTS dp_tasks (
    tenant            TEXT NOT NULL,
    parent_agent_id   TEXT NOT NULL,
    parent_session_id TEXT NOT NULL,
    task_id           TEXT NOT NULL,
    sub_agent_id      TEXT,
    sub_session_id    TEXT,
    status            TEXT NOT NULL,
    terminal          BOOLEAN NOT NULL DEFAULT false,
    result            TEXT,
    error_message     TEXT,
    cancel_requested  BOOLEAN NOT NULL DEFAULT false,
    transport_type    TEXT,
    remote_base_url   TEXT,
    remote_headers    JSONB,
    user_id           TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_checked_at   TIMESTAMPTZ,
    last_updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at      TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant, parent_agent_id, parent_session_id, task_id)
);
CREATE INDEX IF NOT EXISTS idx_dp_tasks_orphan ON dp_tasks (last_updated_at) WHERE NOT terminal;
CREATE INDEX IF NOT EXISTS idx_dp_tasks_session ON dp_tasks (tenant, parent_agent_id, parent_session_id, status);
CREATE INDEX IF NOT EXISTS idx_dp_tasks_delivery ON dp_tasks (tenant, parent_agent_id, parent_session_id) WHERE delivered_at IS NULL;
