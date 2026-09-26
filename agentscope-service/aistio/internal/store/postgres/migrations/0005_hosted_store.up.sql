-- +migrate Up
-- Hosted DistributedStore backends (BaseStore / Lock / Snapshot / Bus / AsyncTools).
-- tenant = {namespace}/{agentName}; dp_kv is user-persistent and never auto-purged.

CREATE TABLE IF NOT EXISTS dp_kv (
    tenant      TEXT   NOT NULL,
    ns_path     TEXT   NOT NULL,
    item_key    TEXT   NOT NULL,
    value       JSONB  NOT NULL,
    version     BIGINT NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, ns_path, item_key)
);

CREATE SEQUENCE IF NOT EXISTS dp_lock_fencing_seq;
CREATE TABLE IF NOT EXISTS dp_locks (
    tenant        TEXT   NOT NULL,
    lock_name     TEXT   NOT NULL,
    owner_token   TEXT   NOT NULL,
    fencing_token BIGINT NOT NULL,
    holder        TEXT,
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant, lock_name)
);

CREATE TABLE IF NOT EXISTS dp_snapshots (
    tenant       TEXT   NOT NULL,
    snapshot_id  TEXT   NOT NULL,
    size_bytes   BIGINT NOT NULL,
    storage_mode TEXT   NOT NULL,
    payload      BYTEA,
    external_url TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    accessed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, snapshot_id)
);

CREATE TABLE IF NOT EXISTS dp_bus_entries (
    id         BIGSERIAL PRIMARY KEY,
    tenant     TEXT     NOT NULL,
    bus_key    TEXT     NOT NULL,
    kind       SMALLINT NOT NULL,
    payload    JSONB    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dp_async_tools (
    tenant       TEXT NOT NULL,
    record_id    TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    tool_name    TEXT,
    tool_call_id TEXT,
    status       TEXT NOT NULL,
    result       TEXT,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, record_id)
);
