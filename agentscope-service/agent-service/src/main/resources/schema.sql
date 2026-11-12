-- Portal-owned tables. Run as the Portal database owner before starting production replicas.
-- This script is also used for the isolated embedded development database.
CREATE TABLE IF NOT EXISTS builder_portal_session (
    id VARCHAR(256) PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    application_id VARCHAR(256),
    runtime_kind VARCHAR(32) NOT NULL,
    command_id VARCHAR(256) NOT NULL,
    revision VARCHAR(256) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    lifecycle_revision BIGINT NOT NULL DEFAULT 1,
    policy TEXT NOT NULL,
    public_view TEXT NOT NULL,
    title VARCHAR(500),
    metadata_json TEXT NOT NULL DEFAULT '{}',
    creation_request_digest VARCHAR(64),
    usage_summary TEXT NOT NULL,
    bootstrap_payload TEXT,
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    next_turn_sequence BIGINT NOT NULL,
    next_event_sequence BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    budget_limits TEXT NOT NULL DEFAULT '{}',
    budget_revision BIGINT NOT NULL DEFAULT 1,
    policy_id VARCHAR(64),
    version BIGINT NOT NULL,
    CONSTRAINT uq_portal_bootstrap_command UNIQUE (command_id)
);
CREATE INDEX IF NOT EXISTS ix_portal_provisioning
    ON builder_portal_session (state, next_attempt_at, claim_until);
CREATE INDEX IF NOT EXISTS ix_portal_session_scope
    ON builder_portal_session (tenant, namespace, owner_id, created_at, id);
ALTER TABLE builder_portal_session ADD COLUMN IF NOT EXISTS root_session_id VARCHAR(256);
ALTER TABLE builder_portal_session ADD COLUMN IF NOT EXISTS parent_session_id VARCHAR(256);
CREATE INDEX IF NOT EXISTS ix_portal_child_root ON builder_portal_session(root_session_id, id);

CREATE TABLE IF NOT EXISTS builder_portal_turn (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    quota_id VARCHAR(64) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    admission_sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reservation_state VARCHAR(32) NOT NULL,
    queued_reservation BOOLEAN NOT NULL,
    release_receipt VARCHAR(256),
    permission_until BIGINT NOT NULL,
    execution_generation BIGINT NOT NULL,
    activation_command_id VARCHAR(64) NOT NULL,
    execution_fence VARCHAR(256),
    grant_until BIGINT NOT NULL,
    command TEXT NOT NULL,
    usage_summary TEXT NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_portal_turn_order UNIQUE (session_id, admission_sequence)
);
CREATE INDEX IF NOT EXISTS ix_portal_turn_delivery
    ON builder_portal_turn (delivery_status, next_attempt_at, claim_until);
CREATE INDEX IF NOT EXISTS ix_portal_turn_session
    ON builder_portal_turn (session_id, admission_sequence);
CREATE INDEX IF NOT EXISTS ix_portal_turn_pending
    ON builder_portal_turn (session_id, status);

CREATE TABLE IF NOT EXISTS builder_portal_quota (
    id VARCHAR(64) PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    application_id VARCHAR(256),
    active_count BIGINT NOT NULL,
    queued_count BIGINT NOT NULL,
    max_concurrent BIGINT NOT NULL,
    token_budget BIGINT NOT NULL,
    tokens_used BIGINT NOT NULL,
    configuration_version BIGINT NOT NULL,
    window_start BIGINT NOT NULL,
    window_count BIGINT NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS builder_portal_event (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(64),
    seq BIGINT NOT NULL,
    type VARCHAR(128) NOT NULL,
    data TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_portal_event_seq UNIQUE (session_id, seq)
);
CREATE INDEX IF NOT EXISTS ix_portal_event_turn
    ON builder_portal_event (session_id, turn_id, seq);

CREATE TABLE IF NOT EXISTS builder_portal_execution_grant (
    id VARCHAR(64) PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    activation_command_id VARCHAR(64) NOT NULL,
    fence VARCHAR(256) NOT NULL,
    valid_until BIGINT NOT NULL,
    CONSTRAINT uq_portal_execution_generation UNIQUE (turn_id, generation)
);

CREATE TABLE IF NOT EXISTS builder_portal_fact_source (
    id VARCHAR(64) PRIMARY KEY,
    last_sequence BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS builder_portal_fact_receipt (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL,
    source_sequence BIGINT NOT NULL,
    digest VARCHAR(64) NOT NULL,
    public_sequence BIGINT NOT NULL,
    CONSTRAINT uq_portal_fact_source_sequence UNIQUE (source_id, source_sequence)
);

CREATE TABLE IF NOT EXISTS builder_portal_resource (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(64),
    kind VARCHAR(32) NOT NULL,
    resource_key VARCHAR(512) NOT NULL,
    valid_from BIGINT NOT NULL,
    data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_resource_page ON builder_portal_resource (session_id, kind, resource_key);
CREATE TABLE IF NOT EXISTS builder_portal_resource_version (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(64),
    kind VARCHAR(32) NOT NULL,
    resource_key VARCHAR(512) NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_through BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_resource_version_page
    ON builder_portal_resource_version (session_id, kind, resource_key, valid_from, valid_through);
CREATE INDEX IF NOT EXISTS ix_portal_resource_version_expiry ON builder_portal_resource_version (expires_at, id);
CREATE TABLE IF NOT EXISTS builder_portal_snapshot (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(64),
    sequence BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    usage TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_snapshot_session ON builder_portal_snapshot (session_id, expires_at);
CREATE INDEX IF NOT EXISTS ix_portal_snapshot_expiry ON builder_portal_snapshot (expires_at, id);

CREATE TABLE IF NOT EXISTS builder_portal_interaction (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    turn_id VARCHAR(64),
    kind VARCHAR(32) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    command TEXT NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    native_status VARCHAR(32) NOT NULL,
    applied_request_ids TEXT NOT NULL,
    accept_until BIGINT NOT NULL,
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    receipt TEXT,
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_interaction_delivery
    ON builder_portal_interaction (delivery_status, next_attempt_at, claim_until);
CREATE INDEX IF NOT EXISTS ix_portal_interaction_session
    ON builder_portal_interaction (session_id, native_status, created_at, id);

CREATE TABLE IF NOT EXISTS builder_portal_session_permission (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    principal_kind VARCHAR(32) NOT NULL,
    principal_id VARCHAR(256) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    policy_version BIGINT NOT NULL,
    policy_id VARCHAR(64),
    valid_until BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_portal_session_permission UNIQUE (session_id, principal_kind, principal_id)
);
CREATE INDEX IF NOT EXISTS ix_portal_session_permission_page
    ON builder_portal_session_permission (principal_kind, principal_id, tenant, namespace, created_at, session_id);

CREATE INDEX IF NOT EXISTS ix_portal_resource_turn_page
    ON builder_portal_resource (session_id, kind, turn_id, resource_key);
CREATE INDEX IF NOT EXISTS ix_portal_resource_version_turn_page
    ON builder_portal_resource_version (session_id, kind, turn_id, resource_key, valid_from, valid_through);

CREATE TABLE IF NOT EXISTS builder_portal_policy (
    id VARCHAR(64) PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    application_id VARCHAR(256),
    policy TEXT NOT NULL,
    configuration_overlay TEXT,
    policy_version BIGINT NOT NULL,
    valid_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_policy_refresh ON builder_portal_policy (next_attempt_at, claim_until);
CREATE INDEX IF NOT EXISTS ix_portal_policy_sessions ON builder_portal_session (policy_id, state, lifecycle, created_at, id);
CREATE INDEX IF NOT EXISTS ix_portal_policy_permissions ON builder_portal_session_permission (policy_id, principal_kind, principal_id, policy_version);

CREATE INDEX IF NOT EXISTS ix_portal_policy_expiry ON builder_portal_policy (valid_until, id);
CREATE INDEX IF NOT EXISTS ix_portal_session_created ON builder_portal_session (created_at, id);

CREATE INDEX IF NOT EXISTS ix_portal_policy_scope ON builder_portal_policy (tenant, namespace, id);
CREATE INDEX IF NOT EXISTS ix_portal_policy_application ON builder_portal_policy (tenant, application_id, id);
CREATE TABLE IF NOT EXISTS builder_portal_policy_change (
    id VARCHAR(64) PRIMARY KEY,
    tenant VARCHAR(128) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    application_id VARCHAR(256) NOT NULL,
    state VARCHAR(32) NOT NULL,
    next_policy_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_portal_policy_change_pending ON builder_portal_policy_change (state, created_at, id);

CREATE TABLE IF NOT EXISTS builder_portal_lifecycle (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_portal_lifecycle_revision UNIQUE (session_id, revision)
);
CREATE INDEX IF NOT EXISTS ix_portal_lifecycle_due ON builder_portal_lifecycle (status, next_attempt_at, claim_until);

CREATE TABLE IF NOT EXISTS builder_portal_artifact_publication (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    data TEXT NOT NULL,
    created_at BIGINT NOT NULL
);

-- Portal subscription cursor, private claim and public delivery metadata.
CREATE TABLE IF NOT EXISTS builder_portal_webhook (
    id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(256) NOT NULL, url VARCHAR(4096) NOT NULL,
    actor TEXT NOT NULL, secret TEXT NOT NULL, event_types TEXT NOT NULL, input_digest VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL, cursor_seq BIGINT NOT NULL, pending_event VARCHAR(256), pending_seq BIGINT NOT NULL,
    delivery_id VARCHAR(64), attempt_count INTEGER NOT NULL, claim_token VARCHAR(64), claim_fence BIGINT NOT NULL,
    claim_until BIGINT NOT NULL, next_attempt_at BIGINT NOT NULL, failure_code VARCHAR(64),
    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, version BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_webhook_session ON builder_portal_webhook(session_id,id);
CREATE INDEX IF NOT EXISTS ix_portal_webhook_limit ON builder_portal_webhook(session_id,state,id);
CREATE INDEX IF NOT EXISTS ix_portal_webhook_due ON builder_portal_webhook(state,next_attempt_at,claim_until,id);
CREATE TABLE IF NOT EXISTS builder_portal_webhook_delivery (
    id VARCHAR(64) PRIMARY KEY, webhook_id VARCHAR(64) NOT NULL, event_id VARCHAR(256) NOT NULL, event_seq BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL, claim_fence BIGINT NOT NULL, claim_token VARCHAR(64) NOT NULL,
    http_status INTEGER NOT NULL, error_code VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_webhook_receipts ON builder_portal_webhook_delivery(webhook_id,id);

-- Copyright 2024-2026 the original author or authors.
-- Licensed under the Apache License, Version 2.0.
CREATE TABLE IF NOT EXISTS builder_portal_checkpoint (
    id VARCHAR(64) PRIMARY KEY,
    source_session_id VARCHAR(256) NOT NULL,
    target_session_id VARCHAR(256) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64),
    claim_token VARCHAR(64),
    claim_until BIGINT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_portal_checkpoint_due ON builder_portal_checkpoint (status,next_attempt_at,claim_until);

