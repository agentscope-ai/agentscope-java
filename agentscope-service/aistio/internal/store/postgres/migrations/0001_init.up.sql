-- +migrate Up
-- aistio runtime schema (storage-design.md §4 + documented deviations)

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version     TEXT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Session 主表
CREATE TABLE IF NOT EXISTS sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          TEXT NOT NULL,
    agent_name          TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    framework           TEXT NOT NULL DEFAULT '',
    framework_version   TEXT,
    phase               TEXT NOT NULL DEFAULT 'active',
    instance_ref        TEXT,
    instance_ip         TEXT,
    team_id             TEXT,
    team_role           TEXT,
    team_context        JSONB,
    started_at          TIMESTAMPTZ,
    last_active_at      TIMESTAMPTZ,
    terminated_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(agent_name, namespace, session_id)
);

CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions(agent_name, namespace);
CREATE INDEX IF NOT EXISTS idx_sessions_phase ON sessions(phase) WHERE phase != 'terminated';
CREATE INDEX IF NOT EXISTS idx_sessions_team ON sessions(team_id, namespace) WHERE team_id IS NOT NULL AND team_id != '';

-- Session 快照（Level 1）
CREATE TABLE IF NOT EXISTS session_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    session_fk              UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    captured_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    message_count           INT,
    prompt_tokens           BIGINT,
    completion_tokens       BIGINT,
    total_tokens            BIGINT,
    context_pressure        REAL,
    is_compacted            BOOLEAN DEFAULT false,
    effective_message_count INT,
    context_hash            TEXT,
    task_summary            JSONB
);

CREATE INDEX IF NOT EXISTS idx_snapshots_session_time ON session_snapshots(session_fk, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_snapshots_pressure ON session_snapshots(captured_at, context_pressure)
    WHERE context_pressure > 0.7;

-- Session 事件流（Level 2）
CREATE TABLE IF NOT EXISTS session_events (
    id              BIGSERIAL PRIMARY KEY,
    session_fk      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    seq             INT NOT NULL,
    event_type      TEXT NOT NULL,
    role            TEXT,
    content         TEXT,
    tool_name       TEXT,
    tool_input      JSONB,
    tool_output     TEXT,
    tokens_in       INT,
    tokens_out      INT,
    duration_ms     INT,
    framework_meta  JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_fk, seq)
);

CREATE INDEX IF NOT EXISTS idx_events_session_time ON session_events(session_fk, occurred_at);
CREATE INDEX IF NOT EXISTS idx_events_type ON session_events(session_fk, event_type);

-- Context 快照（Level 4）
CREATE TABLE IF NOT EXISTS context_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    session_fk              UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    captured_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    context_hash            TEXT NOT NULL,
    system_prompt           TEXT,
    messages                JSONB NOT NULL,
    tools                   JSONB,
    is_compacted            BOOLEAN DEFAULT false,
    compaction_summary      TEXT,
    original_message_count  INT,
    compacted_at            TIMESTAMPTZ,
    total_tokens            INT,
    max_tokens              INT,
    framework               TEXT NOT NULL,
    framework_state         JSONB
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ctx_dedup ON context_snapshots(session_fk, context_hash);
CREATE INDEX IF NOT EXISTS idx_ctx_session_time ON context_snapshots(session_fk, captured_at DESC);

-- Token 用量时序
CREATE TABLE IF NOT EXISTS token_usage_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    session_fk          UUID REFERENCES sessions(id) ON DELETE SET NULL,
    agent_name          TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    model               TEXT,
    provider            TEXT,
    prompt_tokens       BIGINT,
    completion_tokens   BIGINT,
    total_tokens        BIGINT,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_token_agent_time ON token_usage_metrics(agent_name, namespace, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_token_model ON token_usage_metrics(model, recorded_at DESC);

-- Team 消息（替代 TeamMessage CRD）
CREATE TABLE IF NOT EXISTS team_messages (
    id              BIGSERIAL PRIMARY KEY,
    team_name       TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    from_member     TEXT NOT NULL,
    to_member       TEXT,
    content         TEXT NOT NULL,
    kind            TEXT DEFAULT 'message',
    nonce           TEXT,
    delivered       BOOLEAN NOT NULL DEFAULT false,
    delivered_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_team_msg_pending ON team_messages(team_name, namespace, created_at)
    WHERE delivered = false;
CREATE INDEX IF NOT EXISTS idx_team_msg_history ON team_messages(team_name, namespace, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_team_msg_pending_all ON team_messages(created_at)
    WHERE delivered = false;

-- Team 任务（替代 TeamTask CRD）
CREATE TABLE IF NOT EXISTS team_tasks (
    id              BIGSERIAL PRIMARY KEY,
    task_id         TEXT NOT NULL,
    team_name       TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    subject         TEXT NOT NULL,
    description     TEXT,
    state           TEXT NOT NULL DEFAULT 'pending',
    owner           TEXT,
    blocked_by      JSONB,
    result          TEXT,
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    UNIQUE(namespace, team_name, task_id)
);

CREATE INDEX IF NOT EXISTS idx_team_tasks_state ON team_tasks(team_name, namespace, state);

-- 任务状态变更审计
CREATE TABLE IF NOT EXISTS team_task_history (
    id              BIGSERIAL PRIMARY KEY,
    task_fk         BIGINT REFERENCES team_tasks(id) ON DELETE CASCADE,
    team_name       TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    from_state      TEXT,
    to_state        TEXT NOT NULL,
    owner           TEXT,
    transitioned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Agent 运行时指标
CREATE TABLE IF NOT EXISTS agent_metrics (
    id                      BIGSERIAL PRIMARY KEY,
    agent_name              TEXT NOT NULL,
    namespace               TEXT NOT NULL,
    recorded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    active_sessions         INT DEFAULT 0,
    total_messages          BIGINT DEFAULT 0,
    total_tokens            BIGINT DEFAULT 0,
    avg_context_pressure    REAL,
    error_count             INT DEFAULT 0,
    uptime_seconds          BIGINT
);

CREATE INDEX IF NOT EXISTS idx_agent_metrics_time ON agent_metrics(agent_name, namespace, recorded_at DESC);
