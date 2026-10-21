-- Managed checkpoint manifests v1 and durable tool result publications v1.
-- Managed v2 facts. Live MQ / Redis leases are intentionally absent.
CREATE SCHEMA IF NOT EXISTS ma;
CREATE TABLE IF NOT EXISTS ma.admission_quota (
    quota_key VARCHAR(64) PRIMARY KEY,
    window_second BIGINT NOT NULL DEFAULT 0,
    requests BIGINT NOT NULL DEFAULT 0,
    policy_version BIGINT NOT NULL DEFAULT 0,
    max_concurrent BIGINT NOT NULL DEFAULT 0,
    token_budget BIGINT NOT NULL DEFAULT 0,
    baseline_tokens_used BIGINT NOT NULL DEFAULT 0,
    managed_tokens_used BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS ma.session (
    scope_key VARCHAR(64) PRIMARY KEY,
    tenant VARCHAR(1024) NOT NULL,
    namespace VARCHAR(1024) NOT NULL,
    session_id VARCHAR(1024) NOT NULL,
    configuration TEXT NOT NULL,
    lifecycle VARCHAR(32) NOT NULL DEFAULT 'active',
    lifecycle_revision BIGINT NOT NULL DEFAULT 1,
    lifecycle_command_id VARCHAR(256),
    quota_key VARCHAR(64) NOT NULL REFERENCES ma.admission_quota(quota_key),
    execution_epoch BIGINT NOT NULL DEFAULT 0,
    current_turn_id VARCHAR(256),
    publishing_turn_id VARCHAR(256),
    turn_sequence BIGINT NOT NULL DEFAULT 0,
    published_sequence BIGINT NOT NULL DEFAULT 0,
    settled_sequence BIGINT NOT NULL DEFAULT 0,
    native_sequence BIGINT NOT NULL DEFAULT 0,
    native_commit_id VARCHAR(256),
    public_sequence BIGINT NOT NULL DEFAULT 0,
    checkpoint TEXT,
    checkpoint_sequence BIGINT NOT NULL DEFAULT 0,
    usage_summary TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS ma.turn (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    turn_id VARCHAR(256) NOT NULL,
    input_payload TEXT NOT NULL,
    actor TEXT NOT NULL,
    admission_sequence BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    publication_sequence BIGINT,
    broker_message_id VARCHAR(1024),
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    status VARCHAR(32) NOT NULL DEFAULT 'accepted',
    execution_epoch BIGINT NOT NULL DEFAULT 0,
    execution_token VARCHAR(256),
    suspension_id VARCHAR(256),
    resume_id VARCHAR(256),
    model_anchor VARCHAR(256),
    inputs_open BOOLEAN NOT NULL DEFAULT TRUE,
    input_sequence BIGINT NOT NULL DEFAULT 0,
    applied_input_sequence BIGINT NOT NULL DEFAULT 0,
    usage_summary TEXT NOT NULL DEFAULT '{}',
    PRIMARY KEY (scope_key, turn_id),
    UNIQUE (scope_key, admission_sequence),
    UNIQUE (scope_key, publication_sequence)
);
CREATE TABLE IF NOT EXISTS ma.input (
    scope_key VARCHAR(64) NOT NULL,
    input_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    input_sequence BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    actor TEXT NOT NULL,
    action_id VARCHAR(1024),
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'accepted',
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    rejection_reason TEXT,
    PRIMARY KEY (scope_key, input_id),
    FOREIGN KEY (scope_key, turn_id) REFERENCES ma.turn(scope_key, turn_id),
    UNIQUE (scope_key, turn_id, input_sequence)
);
CREATE INDEX IF NOT EXISTS ma_input_pending ON ma.input(scope_key, turn_id, status, input_sequence);
CREATE INDEX IF NOT EXISTS ma_turn_publication ON ma.turn(delivery_status, scope_key, publication_sequence);
CREATE TABLE IF NOT EXISTS ma.turn_suspension (
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    suspension_id VARCHAR(256) NOT NULL,
    execution_epoch BIGINT NOT NULL,
    checkpoint_sequence BIGINT NOT NULL,
    checkpoint TEXT NOT NULL,
    pending_tasks TEXT NOT NULL,
    pending_actions TEXT NOT NULL,
    resume_phase VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'waiting',
    PRIMARY KEY(scope_key,suspension_id),
    FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id)
);
CREATE TABLE IF NOT EXISTS ma.turn_resume (
    scope_key VARCHAR(64) NOT NULL,
    resume_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    suspension_id VARCHAR(256) NOT NULL,
    suspension_epoch BIGINT NOT NULL,
    publication_sequence BIGINT NOT NULL,
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    broker_message_id VARCHAR(1024),
    PRIMARY KEY(scope_key,resume_id),
    UNIQUE(scope_key,suspension_id),
    FOREIGN KEY(scope_key,suspension_id) REFERENCES ma.turn_suspension(scope_key,suspension_id)
);
CREATE INDEX IF NOT EXISTS ma_resume_outbox ON ma.turn_resume(delivery_status,scope_key);
CREATE TABLE IF NOT EXISTS ma.native_event (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    sequence BIGINT NOT NULL,
    event_id VARCHAR(256) NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY(scope_key,sequence),
    UNIQUE(scope_key,event_id)
);
CREATE TABLE IF NOT EXISTS ma.public_event (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    sequence BIGINT NOT NULL,
    source_id VARCHAR(256) NOT NULL,
    type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(scope_key,sequence),
    UNIQUE(scope_key,source_id)
);
CREATE TABLE IF NOT EXISTS ma.tool_task (
    scope_key VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    tool_call_id VARCHAR(256) NOT NULL,
    execution_id VARCHAR(256) NOT NULL,
    tool_name VARCHAR(1024) NOT NULL,
    native_tool_call_id VARCHAR(256),
    arguments TEXT NOT NULL,
    checkpoint_sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ready',
    worker_token VARCHAR(256),
    process_handle TEXT,
    result_hash VARCHAR(64),
    result TEXT,
    PRIMARY KEY(scope_key,task_id),
    FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id),
    UNIQUE(scope_key,turn_id,tool_call_id)
);
CREATE INDEX IF NOT EXISTS ma_tool_pending ON ma.tool_task(status,scope_key);
CREATE TABLE IF NOT EXISTS ma.tool_result_outbox (
    scope_key VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    execution_id VARCHAR(256) NOT NULL,
    result_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    committed_at BIGINT,
    PRIMARY KEY(scope_key,task_id),
    FOREIGN KEY(scope_key,task_id) REFERENCES ma.tool_task(scope_key,task_id)
);
CREATE INDEX IF NOT EXISTS ma_tool_result_pending ON ma.tool_result_outbox(committed_at,next_attempt_at);
CREATE TABLE IF NOT EXISTS ma.tool_stream (
    scope_key VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    execution_id VARCHAR(256) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    chunk_sequence BIGINT NOT NULL DEFAULT 0,
    output_offset BIGINT NOT NULL DEFAULT 0,
    capture_complete BOOLEAN NOT NULL DEFAULT FALSE,
    has_gap BOOLEAN NOT NULL DEFAULT FALSE,
    final_sequence BIGINT,
    consumed_sequence BIGINT NOT NULL DEFAULT 0,
    consumed_chunk BIGINT NOT NULL DEFAULT 0,
    consumed_offset BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(scope_key,task_id),
    FOREIGN KEY(scope_key,task_id) REFERENCES ma.tool_task(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.tool_event (
    scope_key VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    execution_id VARCHAR(256) NOT NULL,
    source_sequence BIGINT NOT NULL,
    event_id VARCHAR(256) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    broker_at BIGINT,
    received_at BIGINT,
    applied_at BIGINT,
    PRIMARY KEY(scope_key,task_id,source_sequence),
    UNIQUE(scope_key,event_id),
    FOREIGN KEY(scope_key,task_id) REFERENCES ma.tool_stream(scope_key,task_id)
);
CREATE INDEX IF NOT EXISTS ma_tool_event_pending ON ma.tool_event(applied_at,next_attempt_at);
CREATE TABLE IF NOT EXISTS ma.tool_event_conflict (
    scope_key VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    source_sequence BIGINT NOT NULL,
    observed_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(scope_key,task_id,source_sequence,observed_hash),
    FOREIGN KEY(scope_key,task_id) REFERENCES ma.tool_stream(scope_key,task_id)
);
CREATE TABLE IF NOT EXISTS ma.action (
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    action_id VARCHAR(256) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'open',
    payload TEXT NOT NULL,
    PRIMARY KEY(scope_key,turn_id,action_id),
    FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id)
);


CREATE INDEX IF NOT EXISTS ma_session_quota ON ma.session(quota_key,scope_key);
CREATE INDEX IF NOT EXISTS ma_turn_pending_scope ON ma.turn(scope_key,status);

CREATE TABLE IF NOT EXISTS ma.budget (
    scope_key VARCHAR(64) PRIMARY KEY REFERENCES ma.session(scope_key),
    revision BIGINT NOT NULL DEFAULT 0,
    limits TEXT NOT NULL,
    summary TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS ma.model_call (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    source_scope VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    call_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    execution_epoch BIGINT NOT NULL,
    model VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    usage TEXT,
    PRIMARY KEY(scope_key,call_id)
);

CREATE TABLE IF NOT EXISTS ma.native_tool_call (
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    tool_call_id VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY(scope_key,turn_id,tool_call_id),
    FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id)
);

CREATE TABLE IF NOT EXISTS ma.public_resource (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    kind VARCHAR(32) NOT NULL,
    resource_key VARCHAR(512) NOT NULL,
    valid_from BIGINT NOT NULL,
    data TEXT NOT NULL,
    turn_id VARCHAR(256),
    PRIMARY KEY(scope_key,kind,resource_key)
);
CREATE TABLE IF NOT EXISTS ma.public_resource_version (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    kind VARCHAR(32) NOT NULL,
    resource_key VARCHAR(512) NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_through BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    data TEXT NOT NULL,
    turn_id VARCHAR(256),
    PRIMARY KEY(scope_key,kind,resource_key,valid_from)
);
CREATE TABLE IF NOT EXISTS ma.snapshot (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    snapshot_id VARCHAR(64) NOT NULL,
    as_of BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    usage TEXT NOT NULL,
    turn_id VARCHAR(256),
    PRIMARY KEY(scope_key,snapshot_id)
);
CREATE INDEX IF NOT EXISTS ma_snapshot_expiry ON ma.snapshot(expires_at,scope_key);
CREATE INDEX IF NOT EXISTS ma_resource_version_expiry ON ma.public_resource_version(scope_key,expires_at);

CREATE TABLE IF NOT EXISTS ma.lifecycle_command (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    command_id VARCHAR(256) NOT NULL,
    desired VARCHAR(32) NOT NULL,
    actor TEXT NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'stopping',
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY(scope_key,command_id)
);
CREATE INDEX IF NOT EXISTS ma_lifecycle_pending ON ma.lifecycle_command(status,scope_key);

CREATE TABLE IF NOT EXISTS ma.file (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    file_id VARCHAR(256) NOT NULL,
    name VARCHAR(512) NOT NULL,
    media_type VARCHAR(512) NOT NULL,
    size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    content BYTEA,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(scope_key,file_id)
);

CREATE TABLE IF NOT EXISTS ma.artifact (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    artifact_id VARCHAR(256) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    data TEXT NOT NULL,
    PRIMARY KEY(scope_key,artifact_id)
);

CREATE TABLE IF NOT EXISTS ma.state_checkpoint (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    native_sequence BIGINT NOT NULL,
    checkpoint_id VARCHAR(256) NOT NULL,
    state_hash VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256),
    run_id VARCHAR(256),
    created_at BIGINT NOT NULL,
    public_sequence BIGINT NOT NULL,
    PRIMARY KEY(scope_key,native_sequence),
    UNIQUE(scope_key,checkpoint_id)
);
CREATE TABLE IF NOT EXISTS ma.checkpoint_file (
    scope_key VARCHAR(64) NOT NULL,
    native_sequence BIGINT NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    PRIMARY KEY(scope_key,native_sequence,file_id),
    FOREIGN KEY(scope_key,native_sequence) REFERENCES ma.state_checkpoint(scope_key,native_sequence),
    FOREIGN KEY(scope_key,file_id) REFERENCES ma.file(scope_key,file_id)
);

CREATE TABLE IF NOT EXISTS ma.checkpoint_operation (
    scope_key VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    operation_id VARCHAR(256) NOT NULL,
    target_scope VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    actor TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    checkpoint_id VARCHAR(256) NOT NULL,
    checkpoint_sequence BIGINT NOT NULL,
    source_head BIGINT NOT NULL,
    target_head BIGINT NOT NULL,
    scan_sequence BIGINT NOT NULL DEFAULT 0,
    validation TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'validating',
    error_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    PRIMARY KEY(scope_key,operation_id)
);
CREATE TABLE IF NOT EXISTS ma.checkpoint_hold (
    scope_key VARCHAR(64) PRIMARY KEY REFERENCES ma.session(scope_key),
    operation_scope VARCHAR(64) NOT NULL,
    operation_id VARCHAR(256) NOT NULL,
    FOREIGN KEY(operation_scope,operation_id) REFERENCES ma.checkpoint_operation(scope_key,operation_id)
);

CREATE TABLE IF NOT EXISTS ma.checkpoint_baseline (
    operation_scope VARCHAR(64) NOT NULL,
    operation_id VARCHAR(256) NOT NULL,
    target_scope VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    native_sequence BIGINT NOT NULL,
    PRIMARY KEY(operation_scope,operation_id),
    FOREIGN KEY(operation_scope,operation_id) REFERENCES ma.checkpoint_operation(scope_key,operation_id)
);

-- Private, immutable workspace images. Never exposed through files or public events.
CREATE TABLE IF NOT EXISTS ma.checkpoint_workspace (
    scope_key VARCHAR(64) NOT NULL,
    native_sequence BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    manifest TEXT NOT NULL,
    content BYTEA NOT NULL,
    PRIMARY KEY(scope_key,native_sequence),
    FOREIGN KEY(scope_key,native_sequence) REFERENCES ma.state_checkpoint(scope_key,native_sequence)
);
CREATE TABLE IF NOT EXISTS ma.checkpoint_workspace_step (
    operation_scope VARCHAR(64) NOT NULL,
    operation_id VARCHAR(256) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    sandbox_scope VARCHAR(64) NOT NULL REFERENCES ma.session(scope_key),
    execution_id VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'prepared',
    result_hash VARCHAR(64),
    PRIMARY KEY(operation_scope,operation_id,phase),
    FOREIGN KEY(operation_scope,operation_id) REFERENCES ma.checkpoint_operation(scope_key,operation_id)
);
