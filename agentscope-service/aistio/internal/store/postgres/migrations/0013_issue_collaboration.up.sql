-- +migrate Up
-- Issue-first collaboration plane. Relationships are enforced by application
-- transactions so runtime/task history can be retained independently.

CREATE TABLE issues (
    id                  UUID PRIMARY KEY,
    tenant              TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    identifier          TEXT,
    title               TEXT NOT NULL,
    description         TEXT,
    status              TEXT NOT NULL,
    priority            TEXT NOT NULL DEFAULT 'none',
	    kind                TEXT NOT NULL DEFAULT 'user_work',
	    visibility          TEXT NOT NULL DEFAULT 'work_hub',
	    completion_policy   TEXT NOT NULL DEFAULT 'review',
    assignee_type       TEXT,
    assignee_ref        TEXT,
    execution_target_type TEXT,
    execution_target_ref  TEXT,
    creator_type        TEXT NOT NULL,
    creator_ref         TEXT,
    parent_issue_id     UUID,
    acceptance_criteria JSONB NOT NULL DEFAULT '[]'::jsonb,
    context_refs        JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_type         TEXT,
    source_ref          TEXT,
    due_at              TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    archived_at         TIMESTAMPTZ
);

CREATE TABLE comments (
    id                  UUID PRIMARY KEY,
    tenant              TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    issue_id            UUID NOT NULL,
    parent_id           UUID,
    thread_root_id      UUID NOT NULL,
    author_type         TEXT NOT NULL,
    author_ref          TEXT,
    content             TEXT NOT NULL,
    type                TEXT NOT NULL DEFAULT 'comment',
    source_task_id      UUID,
    source_attempt_id UUID,
    version             BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by_type    TEXT,
    resolved_by_ref     TEXT,
    deleted_at          TIMESTAMPTZ
);

CREATE TABLE comment_mentions (
    id          UUID PRIMARY KEY,
    tenant      TEXT NOT NULL,
    namespace   TEXT NOT NULL,
    issue_id    UUID NOT NULL,
    comment_id  UUID NOT NULL,
    target_type TEXT NOT NULL,
    target_ref  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE comment_routes (
    id          UUID PRIMARY KEY,
    tenant      TEXT NOT NULL,
    namespace   TEXT NOT NULL,
    issue_id    UUID NOT NULL,
    comment_id  UUID NOT NULL,
	comment_version BIGINT NOT NULL,
    target_type TEXT NOT NULL,
    target_ref  TEXT NOT NULL,
    route_type  TEXT NOT NULL,
    outcome     TEXT NOT NULL,
    task_id     UUID,
    reason_code TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_tasks (
    id                    UUID PRIMARY KEY,
    tenant                TEXT NOT NULL,
	    namespace             TEXT NOT NULL,
	    issue_id              UUID NOT NULL,
	    orchestration_run_id  UUID NOT NULL,
	    run_node_id           UUID NOT NULL,
	    current_attempt_id    UUID,
    agent_id             TEXT NOT NULL,
    status                TEXT NOT NULL,
    priority              INT NOT NULL DEFAULT 0,
    trigger_type          TEXT NOT NULL,
    trigger_comment_id    UUID,
    team_id               UUID,
    team_role             TEXT,
    is_leader_task        BOOLEAN NOT NULL DEFAULT false,
    parent_task_id        UUID,
    delegated_from_task_id UUID,
    retry_of_task_id      UUID,
    rerun_of_task_id      UUID,
    originator_type       TEXT NOT NULL,
    originator_ref        TEXT,
    accountable_human_ref TEXT,
	causation_id          TEXT,
	correlation_id        TEXT,
	hop_count             INT NOT NULL DEFAULT 0,
	team_depth            INT NOT NULL DEFAULT 0,
    runtime_binding       JSONB,
    session_id            TEXT,
    result                JSONB,
    error_code            TEXT,
    error_message         TEXT,
    wait_reason           TEXT,
    version               BIGINT NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at         TIMESTAMPTZ,
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ
);

CREATE TABLE execution_attempts (
    id                    UUID PRIMARY KEY,
    agent_task_id         UUID NOT NULL,
	    tenant                TEXT NOT NULL,
	    namespace             TEXT NOT NULL,
	    run_id                UUID NOT NULL,
	    node_id               UUID NOT NULL,
	    attempt               INT NOT NULL,
	    dispatch_generation   BIGINT NOT NULL DEFAULT 1,
	    backend_kind          TEXT NOT NULL,
	    runtime_binding       JSONB NOT NULL,
    runtime_profile_name  TEXT,
    runtime_pool_name     TEXT,
	    required_capabilities JSONB,
	    host_id               UUID,
	    agent_instance_id     UUID,
	    managed_owner_ref     TEXT,
	    managed_agent_ref     TEXT,
	    session_id            TEXT,
	    turn_id               TEXT,
    provider_session_id   TEXT,
    workspace_key         TEXT,
    state                 TEXT NOT NULL,
    lease_owner           TEXT,
    lease_token           TEXT,
	    fencing_token         BIGINT NOT NULL DEFAULT 0,
	    lease_expires_at      TIMESTAMPTZ,
	    heartbeat_at          TIMESTAMPTZ,
	    cancel_requested_at   TIMESTAMPTZ,
    checkpoint            JSONB,
    result                JSONB,
	    failure_code          TEXT,
	    failure_message       TEXT,
	    usage                 JSONB,
	    agent_id              UUID,
	    binding_id            UUID,
    version               BIGINT NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ
);

CREATE TABLE agent_task_inputs (
    id                  UUID PRIMARY KEY,
    tenant              TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    task_id             UUID NOT NULL,
    comment_id          UUID NOT NULL,
    comment_version     BIGINT NOT NULL,
    sequence            BIGINT NOT NULL,
    state               TEXT NOT NULL,
    delivered_at        TIMESTAMPTZ,
    acknowledged_at     TIMESTAMPTZ,
    processed_at        TIMESTAMPTZ,
    response_comment_id UUID,
	attempts            INT NOT NULL DEFAULT 0,
	last_error          TEXT,
	next_attempt_at     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id               UUID PRIMARY KEY,
    tenant           TEXT NOT NULL,
    namespace        TEXT NOT NULL,
    name             TEXT NOT NULL,
    description      TEXT,
	    instructions    TEXT,
	    status          TEXT NOT NULL DEFAULT 'active',
    leader_agent_id TEXT NOT NULL,
    policy           JSONB NOT NULL DEFAULT '{}'::jsonb,
    version          BIGINT NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at      TIMESTAMPTZ
);

CREATE TABLE team_members (
    id                      UUID PRIMARY KEY,
    tenant                  TEXT NOT NULL,
    namespace               TEXT NOT NULL,
    team_id                 UUID NOT NULL,
    agent_id               TEXT NOT NULL,
    role                    TEXT NOT NULL,
    instructions            TEXT,
    capability_requirements JSONB,
    runtime_binding_policy JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at             TIMESTAMPTZ
);

CREATE TABLE artifacts (
    id                  UUID PRIMARY KEY,
    tenant              TEXT NOT NULL,
    namespace           TEXT NOT NULL,
    storage_provider    TEXT NOT NULL,
    storage_key         TEXT NOT NULL,
    filename            TEXT NOT NULL,
    content_type        TEXT NOT NULL,
    size_bytes          BIGINT NOT NULL,
    checksum            TEXT NOT NULL,
    uploader_type       TEXT NOT NULL,
    uploader_ref        TEXT,
    source_task_id      UUID,
    source_attempt_id UUID,
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ
);

CREATE TABLE artifact_links (
    artifact_id UUID NOT NULL,
    target_type TEXT NOT NULL,
    target_ref  TEXT NOT NULL,
    relation    TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE issue_subscribers (
    issue_id       UUID NOT NULL,
    tenant         TEXT NOT NULL,
    namespace      TEXT NOT NULL,
	subscriber_type TEXT NOT NULL,
    subscriber_ref TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approvals (
	id             UUID PRIMARY KEY,
	tenant         TEXT NOT NULL,
	namespace      TEXT NOT NULL,
	target_type    TEXT NOT NULL,
		target_ref     TEXT NOT NULL,
		issue_id       UUID,
		run_id         UUID,
		run_node_id    UUID,
	requested_by_type TEXT NOT NULL,
	requested_by_ref  TEXT,
	approver_ref   TEXT NOT NULL,
	status         TEXT NOT NULL,
	reason         TEXT,
	request        JSONB,
	decision       JSONB,
	decided_by_type TEXT,
	decided_by_ref  TEXT,
	version        BIGINT NOT NULL DEFAULT 1,
	created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
	updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
	decided_at     TIMESTAMPTZ
);

CREATE TABLE inbox_items (
    id            UUID PRIMARY KEY,
    tenant        TEXT NOT NULL,
    namespace     TEXT NOT NULL,
	recipient_type TEXT NOT NULL DEFAULT 'human',
    recipient_ref TEXT NOT NULL,
    type          TEXT NOT NULL,
    severity      TEXT NOT NULL,
    issue_id      UUID,
    comment_id    UUID,
    approval_id   UUID,
    actor_type    TEXT NOT NULL,
    actor_ref     TEXT,
    title         TEXT NOT NULL,
    body          TEXT,
    details       JSONB,
    read          BOOLEAN NOT NULL DEFAULT false,
    archived      BOOLEAN NOT NULL DEFAULT false,
    dedupe_key    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE activity_log (
    id             UUID PRIMARY KEY,
    tenant         TEXT NOT NULL,
    namespace      TEXT NOT NULL,
    issue_id       UUID,
    actor_type     TEXT NOT NULL,
    actor_ref      TEXT,
    action         TEXT NOT NULL,
    object_type    TEXT NOT NULL,
    object_ref     TEXT NOT NULL,
    causation_id   TEXT,
    correlation_id TEXT,
    details        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- v5 external Work Source projection and reliable synchronization state.
CREATE TABLE work_sources (
    id UUID PRIMARY KEY,
    tenant TEXT NOT NULL,
    namespace TEXT NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    configuration JSONB,
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    UNIQUE (tenant, namespace, kind, name)
);

CREATE TABLE issue_external_refs (
    work_source_id UUID NOT NULL REFERENCES work_sources(id),
    issue_id UUID NOT NULL REFERENCES issues(id),
    external_id TEXT NOT NULL,
    external_number TEXT,
    external_url TEXT,
    external_version TEXT,
    projection JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_source_id, external_id),
    UNIQUE (work_source_id, issue_id)
);

CREATE TABLE comment_external_refs (
    work_source_id UUID NOT NULL REFERENCES work_sources(id),
    comment_id UUID NOT NULL REFERENCES comments(id),
    external_id TEXT,
    external_version TEXT,
    sync_state TEXT NOT NULL,
    last_error TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_source_id, comment_id)
);
CREATE UNIQUE INDEX uq_comment_external_refs_external_id
    ON comment_external_refs(work_source_id, external_id)
    WHERE external_id IS NOT NULL;

CREATE TABLE external_links (
    id UUID PRIMARY KEY,
    work_source_id UUID NOT NULL REFERENCES work_sources(id),
    issue_id UUID NOT NULL REFERENCES issues(id),
    type TEXT NOT NULL,
    external_id TEXT,
    url TEXT NOT NULL,
    title TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (work_source_id, issue_id, type, external_id)
);

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    work_source_id UUID NOT NULL REFERENCES work_sources(id),
    delivery_id TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    last_error TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    UNIQUE (work_source_id, delivery_id)
);

CREATE TABLE team_proposals (
    id UUID PRIMARY KEY,
    issue_id UUID NOT NULL REFERENCES issues(id),
    tenant TEXT NOT NULL,
    namespace TEXT NOT NULL,
    requirements JSONB NOT NULL,
    members JSONB NOT NULL,
    status TEXT NOT NULL,
    run_id UUID,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
