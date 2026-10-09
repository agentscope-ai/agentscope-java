CREATE TABLE orchestration_definitions (
    id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    name TEXT NOT NULL, description TEXT, draft_spec JSONB NOT NULL,
    draft_version BIGINT NOT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 1,
    created_by_type TEXT NOT NULL, created_by_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ
);

CREATE TABLE orchestration_revisions (
    id UUID NOT NULL, definition_id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    revision BIGINT NOT NULL, spec JSONB NOT NULL, checksum TEXT NOT NULL,
    published_by_type TEXT NOT NULL, published_by_ref TEXT,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orchestration_runs (
    id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL, root_issue_id UUID NOT NULL,
    mode TEXT NOT NULL, definition_revision_id UUID, parent_run_id UUID, parent_node_id UUID,
    rerun_of_run_id UUID, trigger_type TEXT NOT NULL, trigger_ref TEXT, idempotency_key TEXT,
    input JSONB, variables JSONB, output JSONB, policy_snapshot JSONB, usage JSONB,
    state TEXT NOT NULL, wait_reason TEXT, failure_code TEXT, failure_message TEXT,
    version BIGINT NOT NULL DEFAULT 1, created_by_type TEXT NOT NULL, created_by_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ
);

CREATE TABLE orchestration_run_nodes (
    id UUID NOT NULL, run_id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    node_key TEXT NOT NULL, definition_node_key TEXT, type TEXT NOT NULL, role TEXT,
    issue_id UUID, state TEXT NOT NULL, config JSONB, input JSONB, output JSONB,
    iteration INT NOT NULL DEFAULT 1, wait_reason TEXT, failure_code TEXT, failure_message TEXT,
    version BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ
);

CREATE TABLE orchestration_run_edges (
    id UUID NOT NULL, run_id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    from_node_id UUID NOT NULL, to_node_id UUID NOT NULL, on_states JSONB NOT NULL,
    condition TEXT, ordinal INT NOT NULL DEFAULT 0, metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orchestration_run_team_snapshots (
    run_id UUID NOT NULL, team_id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    snapshot JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orchestration_run_events (
    id UUID NOT NULL, run_id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL,
    sequence BIGINT NOT NULL, node_id UUID, agent_task_id UUID, attempt_id UUID,
    type TEXT NOT NULL, actor_type TEXT NOT NULL, actor_ref TEXT, payload JSONB,
    causation_id TEXT, correlation_id TEXT, idempotency_key TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_runtime_policies (
    id UUID NOT NULL, tenant TEXT NOT NULL, namespace TEXT NOT NULL, agent_id TEXT NOT NULL,
    candidates JSONB NOT NULL, selection_mode TEXT NOT NULL DEFAULT 'ordered',
    fallback_mode TEXT NOT NULL DEFAULT 'disabled', max_concurrency INT NOT NULL DEFAULT 0,
    queue_timeout_seconds BIGINT NOT NULL DEFAULT 7200, attempt_timeout_seconds BIGINT NOT NULL DEFAULT 0,
    retry_policy JSONB, version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ
);
