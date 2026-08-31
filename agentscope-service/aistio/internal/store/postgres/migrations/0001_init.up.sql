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
	 tenant              TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    agent_id            UUID,
    binding_id          UUID,
    agent_instance_id   UUID,
    instance_generation BIGINT NOT NULL DEFAULT 0,
    agent_name          TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    framework           TEXT NOT NULL DEFAULT '',
    framework_version   TEXT,
    phase               TEXT NOT NULL DEFAULT 'active',
    instance_ref        TEXT,
    instance_ip         TEXT,
    agent_task_id       UUID,
    origin_type         TEXT,
    origin_ref          TEXT,
    task_context        JSONB,
    started_at          TIMESTAMPTZ,
    last_active_at      TIMESTAMPTZ,
    terminated_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- Session 快照（Level 1）
CREATE TABLE IF NOT EXISTS session_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    session_fk              UUID NOT NULL,
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


-- Session 事件流（Level 2）
CREATE TABLE IF NOT EXISTS session_events (
    id              BIGSERIAL PRIMARY KEY,
    session_fk      UUID NOT NULL,
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
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- Context 快照（Level 4）
CREATE TABLE IF NOT EXISTS context_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    session_fk              UUID NOT NULL,
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


-- Token 用量时序
CREATE TABLE IF NOT EXISTS token_usage_metrics (
    id                  BIGSERIAL PRIMARY KEY,
	 tenant              TEXT NOT NULL,
    session_fk          UUID,
    agent_id            UUID,
    agent_name          TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    model               TEXT,
    provider            TEXT,
    prompt_tokens       BIGINT,
    completion_tokens   BIGINT,
    total_tokens        BIGINT,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- Agent 运行时指标
CREATE TABLE IF NOT EXISTS agent_metrics (
    id                      BIGSERIAL PRIMARY KEY,
	 tenant                  TEXT NOT NULL,
    agent_id               UUID,
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
