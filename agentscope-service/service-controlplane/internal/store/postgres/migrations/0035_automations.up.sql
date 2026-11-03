CREATE TABLE automations (
    id UUID PRIMARY KEY, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    name TEXT NOT NULL, description TEXT, enabled BOOLEAN NOT NULL DEFAULT true,
    trigger_type TEXT NOT NULL, trigger_config JSONB, action_type TEXT NOT NULL,
    action_config JSONB NOT NULL, webhook_secret_hash TEXT, next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 1,
    created_by_type TEXT NOT NULL, created_by_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ
);

CREATE TABLE automation_runs (
    id UUID PRIMARY KEY, automation_id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    trigger_type TEXT NOT NULL, trigger_ref TEXT, idempotency_key TEXT NOT NULL,
    status TEXT NOT NULL, issue_id UUID, agent_task_id UUID, orchestration_run_id UUID, input JSONB, output JSONB,
    error_code TEXT, error_message TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
