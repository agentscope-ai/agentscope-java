-- +migrate Up
-- Runtime registry and durable domain-event outbox. Logical collaboration
-- work and physical attempts are defined by the Issue schema migration.

CREATE SEQUENCE IF NOT EXISTS execution_attempt_fencing_seq;

CREATE TABLE IF NOT EXISTS agents (
    id           UUID PRIMARY KEY,
    tenant       TEXT NOT NULL,
    namespace    TEXT NOT NULL,
    agent_key    TEXT NOT NULL,
    display_name TEXT NOT NULL,
    description  TEXT,
    owner_type   TEXT,
    owner_ref    TEXT,
    status       TEXT NOT NULL,
    capabilities JSONB,
    labels       JSONB,
    metadata     JSONB,
    version      BIGINT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at  TIMESTAMPTZ,
    UNIQUE (tenant, namespace, agent_key)
);

CREATE TABLE IF NOT EXISTS agent_bindings (
    id            UUID PRIMARY KEY,
    agent_id      UUID NOT NULL REFERENCES agents(id),
    tenant        TEXT NOT NULL,
    namespace     TEXT NOT NULL,
    kind          TEXT NOT NULL,
    configuration JSONB NOT NULL,
    priority      INT NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    version       BIGINT NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS agent_registration_credentials (
    id         UUID PRIMARY KEY,
    agent_id   UUID NOT NULL REFERENCES agents(id),
    token_hash BYTEA NOT NULL,
    status     TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    rotated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (token_hash)
);

CREATE TABLE IF NOT EXISTS agent_instances (
    id                UUID PRIMARY KEY,
    tenant            TEXT NOT NULL,
    namespace         TEXT NOT NULL,
    agent_id          UUID NOT NULL REFERENCES agents(id),
    binding_id        UUID NOT NULL REFERENCES agent_bindings(id),
    backend_kind      TEXT NOT NULL,
    instance_key      TEXT NOT NULL,
    framework         TEXT,
    framework_version TEXT,
    sdk_version       TEXT,
    capabilities      JSONB,
    labels            JSONB,
    routing_key       TEXT,
    health            TEXT NOT NULL,
    capacity          INT NOT NULL DEFAULT 0,
    active_sessions   INT NOT NULL DEFAULT 0,
    last_seen_at      TIMESTAMPTZ NOT NULL,
    generation        BIGINT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS runtime_profiles (
    id            UUID PRIMARY KEY,
    tenant        TEXT NOT NULL,
    namespace     TEXT NOT NULL,
    name          TEXT NOT NULL,
    provider      TEXT NOT NULL,
    runtime       TEXT,
    configuration JSONB,
    requirements  JSONB,
    version       BIGINT NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS runtime_pools (
    id            UUID PRIMARY KEY,
    tenant        TEXT NOT NULL,
    namespace     TEXT NOT NULL,
    name          TEXT NOT NULL,
    host_selector JSONB,
    configuration JSONB,
    version       BIGINT NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS runtime_hosts (
    id               UUID PRIMARY KEY,
    tenant           TEXT NOT NULL,
    namespace        TEXT NOT NULL,
    host_key         TEXT NOT NULL,
    pool_name        TEXT NOT NULL,
    daemon_version   TEXT,
    os               TEXT,
    arch             TEXT,
    labels           JSONB,
    capabilities     JSONB,
    state            TEXT NOT NULL,
    capacity         INT NOT NULL DEFAULT 0,
    active           INT NOT NULL DEFAULT 0,
    last_seen_at     TIMESTAMPTZ NOT NULL,
    lease_generation BIGINT NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS control_outbox (
    id             UUID PRIMARY KEY,
    tenant         TEXT NOT NULL,
    namespace      TEXT NOT NULL DEFAULT '',
    aggregate_type TEXT NOT NULL,
    aggregate_id   TEXT NOT NULL,
    event_type     TEXT NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    actor_type     TEXT NOT NULL DEFAULT 'system',
    actor_ref      TEXT,
    causation_id   TEXT,
    correlation_id TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload        JSONB NOT NULL,
    dedupe_key     TEXT,
    attempts       INT NOT NULL DEFAULT 0,
    available_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by     TEXT,
    claimed_until  TIMESTAMPTZ,
    delivered_at   TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS endpoints (
    id UUID PRIMARY KEY,
    tenant TEXT NOT NULL,
    namespace TEXT NOT NULL,
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    target_type TEXT NOT NULL,
    target_ref UUID NOT NULL,
    invocation_mode TEXT NOT NULL,
    input_schema JSONB,
    output_schema JSONB,
    event_schema_version TEXT NOT NULL DEFAULT 'v1',
    timeout_seconds INT NOT NULL DEFAULT 300,
    max_payload_bytes BIGINT NOT NULL DEFAULT 1048576,
    auth_policy JSONB NOT NULL,
    rate_limit JSONB,
    active_release_id UUID,
    active_release INT NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'draft',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    UNIQUE (tenant, namespace, name)
);

CREATE TABLE IF NOT EXISTS endpoint_releases (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    release_number INT NOT NULL,
    target_type TEXT NOT NULL,
    target_ref UUID NOT NULL,
    created_by_type TEXT NOT NULL,
    created_by_ref TEXT,
    reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (endpoint_id, release_number)
);
CREATE INDEX IF NOT EXISTS endpoint_releases_endpoint_created
    ON endpoint_releases(endpoint_id, release_number DESC);

CREATE TABLE IF NOT EXISTS endpoint_credentials (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    name TEXT NOT NULL,
    key_prefix TEXT NOT NULL,
    secret_hash BYTEA NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    scopes JSONB,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    rotated_from UUID REFERENCES endpoint_credentials(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    UNIQUE (endpoint_id, name),
    UNIQUE (endpoint_id, key_prefix)
);

CREATE TABLE IF NOT EXISTS endpoint_conversations (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    agent_id UUID NOT NULL REFERENCES agents(id),
    session_id TEXT NOT NULL,
    binding_id UUID NOT NULL REFERENCES agent_bindings(id),
    agent_instance_id UUID REFERENCES agent_instances(id),
    instance_generation BIGINT NOT NULL DEFAULT 0,
    external_session_ref TEXT,
    status TEXT NOT NULL DEFAULT 'active',
    principal_ref TEXT NOT NULL,
    last_turn_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (endpoint_id, session_id)
);

CREATE TABLE IF NOT EXISTS endpoint_rate_windows (
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    principal_ref TEXT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    request_count INT NOT NULL DEFAULT 1,
    PRIMARY KEY (endpoint_id, principal_ref, window_start)
);

CREATE TABLE IF NOT EXISTS endpoint_invocations (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id),
    mode TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    principal_ref TEXT NOT NULL,
    idempotency_key TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    conversation_id UUID REFERENCES endpoint_conversations(id),
    turn_id UUID,
    session_id TEXT,
    issue_id UUID,
    run_id UUID,
    input JSONB,
    result JSONB,
    error_code TEXT,
    error_message TEXT,
    correlation_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS endpoint_invocations_idempotency
    ON endpoint_invocations(endpoint_id, mode, principal_ref, idempotency_key)
    WHERE idempotency_key <> '';
CREATE INDEX IF NOT EXISTS endpoint_invocations_endpoint_created
    ON endpoint_invocations(endpoint_id, created_at DESC);
