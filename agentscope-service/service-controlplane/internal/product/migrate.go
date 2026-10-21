// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"context"
	"crypto/sha256"
	"fmt"
	"log"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/policychanges"
)

const migrationSQL = `
CREATE SCHEMA IF NOT EXISTS cp;
SET search_path TO cp;

CREATE TABLE IF NOT EXISTS users (
    user_id       TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    roles_csv     TEXT NOT NULL DEFAULT 'user',
    created_at    BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS agents (
    row_id            BIGSERIAL PRIMARY KEY,
    owner_id          TEXT NOT NULL,
    agent_id          TEXT NOT NULL,
    workspace_path    TEXT,
    name              TEXT NOT NULL,
    description       TEXT,
    sys_prompt        TEXT,
    model             TEXT,
    max_iters         INT,
    tools_json        TEXT,
    mcp_servers_json  TEXT,
    skills_json       TEXT,
    multiagent_json   TEXT,
    head_version      INT NOT NULL DEFAULT 1,
    archived_at       BIGINT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    UNIQUE (owner_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_versions (
    row_id         BIGSERIAL PRIMARY KEY,
    owner_id       TEXT NOT NULL,
    agent_id       TEXT NOT NULL,
    version        INT NOT NULL,
    snapshot_json  TEXT NOT NULL,
    created_at     BIGINT NOT NULL,
    UNIQUE (owner_id, agent_id, version)
);

CREATE TABLE IF NOT EXISTS environments (
    row_id         BIGSERIAL PRIMARY KEY,
    environment_id TEXT NOT NULL UNIQUE,
    owner_id       TEXT NOT NULL,
    name           TEXT NOT NULL,
    type           TEXT NOT NULL DEFAULT 'local',
    config_json    TEXT,
    api_key_hash   TEXT,
    archived_at    BIGINT,
    created_at     BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    row_id                BIGSERIAL PRIMARY KEY,
    session_id            TEXT NOT NULL UNIQUE,
    owner_id              TEXT NOT NULL,
    agent_id              TEXT NOT NULL,
    agent_owner_id        TEXT,
    agent_version         INT,
    agent_ref_type        TEXT,
    agent_overrides_json  TEXT,
    environment_id        TEXT NOT NULL,
    external_key          TEXT,
    memory_store_ids_json TEXT,
    vault_ids_json        TEXT,
    resources_json        TEXT,
    status                TEXT NOT NULL DEFAULT 'idle',
    stop_reason_json      TEXT,
    version               INT NOT NULL DEFAULT 1,
    archived_at           BIGINT,
    created_at            BIGINT NOT NULL,
    updated_at            BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_owner ON sessions (owner_id);
CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions (owner_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_sessions_external ON sessions (owner_id, agent_id, environment_id, external_key);

CREATE TABLE IF NOT EXISTS memory_stores (
    row_id      BIGSERIAL PRIMARY KEY,
    store_id    TEXT NOT NULL UNIQUE,
    owner_id    TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT,
    archived_at BIGINT,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS memories (
    row_id       BIGSERIAL PRIMARY KEY,
    memory_id    TEXT NOT NULL UNIQUE,
    store_id     TEXT NOT NULL,
    path         TEXT NOT NULL,
    content      TEXT NOT NULL DEFAULT '',
    head_version INT NOT NULL DEFAULT 1,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL,
    UNIQUE (store_id, path)
);

CREATE TABLE IF NOT EXISTS memory_versions (
    row_id     BIGSERIAL PRIMARY KEY,
    memory_id  TEXT NOT NULL,
    version    INT NOT NULL,
    content    TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (memory_id, version)
);

CREATE TABLE IF NOT EXISTS vaults (
    row_id       BIGSERIAL PRIMARY KEY,
    vault_id     TEXT NOT NULL UNIQUE,
    owner_id     TEXT NOT NULL,
    display_name TEXT NOT NULL,
    metadata_json TEXT,
    archived_at  BIGINT,
    created_at   BIGINT NOT NULL,
    updated_at   BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS vault_credentials (
    row_id      BIGSERIAL PRIMARY KEY,
    credential_id TEXT NOT NULL UNIQUE,
    vault_id    TEXT NOT NULL,
    type        TEXT NOT NULL,
    label       TEXT NOT NULL,
    target      TEXT NOT NULL,
    ciphertext  BYTEA NOT NULL,
    created_at  BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS deployments (
    row_id           BIGSERIAL PRIMARY KEY,
    deployment_id    TEXT NOT NULL UNIQUE,
    owner_id         TEXT NOT NULL,
    name             TEXT NOT NULL,
    agent_id         TEXT NOT NULL,
    agent_version    INT,
    environment_id   TEXT NOT NULL,
    trigger_type     TEXT NOT NULL,
    cron_expression  TEXT,
    webhook_token    TEXT,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at      BIGINT,
    last_session_id  TEXT,
    last_status      TEXT,
    last_hands_stats_json TEXT,
    archived_at      BIGINT,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_deployments_webhook ON deployments (webhook_token);

CREATE TABLE IF NOT EXISTS resource_shares (
    row_id          BIGSERIAL PRIMARY KEY,
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    grantee_user_id TEXT NOT NULL,
    permission      TEXT NOT NULL DEFAULT 'RUN',
    created_at      BIGINT NOT NULL,
    created_by      TEXT,
    UNIQUE (resource_type, resource_id, grantee_user_id)
);

CREATE TABLE IF NOT EXISTS channels (
    channel_id       TEXT PRIMARY KEY,
    owner_id         TEXT NOT NULL,
    type             TEXT NOT NULL,
    dm_scope         TEXT,
    default_agent_id TEXT,
    disabled         BOOLEAN NOT NULL DEFAULT FALSE,
    properties_json  TEXT,
    bindings_json    TEXT,
    runtime_started  BOOLEAN NOT NULL DEFAULT FALSE,
    runtime_error    TEXT,
    runtime_updated_at BIGINT,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_channels_owner ON channels (owner_id);

CREATE TABLE IF NOT EXISTS agent_bindings (
    row_id         BIGSERIAL PRIMARY KEY,
    owner_id       TEXT NOT NULL,
    agent_id       TEXT NOT NULL,
    channel_id     TEXT NOT NULL,
    binding_index  INT NOT NULL,
    tier           TEXT NOT NULL,
    payload_json   TEXT,
    created_at     BIGINT NOT NULL,
    UNIQUE (owner_id, agent_id, channel_id, binding_index)
);
CREATE INDEX IF NOT EXISTS idx_agent_bindings_agent ON agent_bindings (owner_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_bindings_channel ON agent_bindings (owner_id, channel_id);

CREATE TABLE IF NOT EXISTS files (
    row_id       BIGSERIAL PRIMARY KEY,
    file_id      TEXT NOT NULL UNIQUE,
    owner_id     TEXT NOT NULL,
    filename     TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'text/plain',
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    content      TEXT NOT NULL DEFAULT '',
    created_at   BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_files_owner ON files (owner_id);
`

const migrationAlterSQL = `
ALTER TABLE channels ADD COLUMN IF NOT EXISTS runtime_started BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS runtime_error TEXT;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS runtime_updated_at BIGINT;

ALTER TABLE vault_credentials ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1;

ALTER TABLE agents ADD COLUMN IF NOT EXISTS workspace_id TEXT;

CREATE TABLE IF NOT EXISTS workspaces (
    row_id            BIGSERIAL PRIMARY KEY,
    owner_id          TEXT NOT NULL,
    workspace_id      TEXT NOT NULL,
    name              TEXT NOT NULL,
    description       TEXT,
    tools_json        TEXT,
    mcp_servers_json  TEXT,
    skills_json       TEXT,
    head_version      INT NOT NULL DEFAULT 1,
    archived_at       BIGINT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    UNIQUE (owner_id, workspace_id)
);
CREATE INDEX IF NOT EXISTS idx_workspaces_owner ON workspaces (owner_id);

CREATE TABLE IF NOT EXISTS workspace_files (
    row_id       BIGSERIAL PRIMARY KEY,
    owner_id     TEXT NOT NULL,
    scope_type   TEXT NOT NULL,
    scope_id     TEXT NOT NULL,
    path         TEXT NOT NULL,
    content      TEXT NOT NULL DEFAULT '',
    updated_at   BIGINT NOT NULL,
    UNIQUE (owner_id, scope_type, scope_id, path)
);
CREATE INDEX IF NOT EXISTS idx_workspace_files_scope ON workspace_files (owner_id, scope_type, scope_id);

CREATE TABLE IF NOT EXISTS marketplaces (
    row_id          BIGSERIAL PRIMARY KEY,
    owner_id        TEXT NOT NULL,
    marketplace_id  TEXT NOT NULL,
    name            TEXT NOT NULL,
    type            TEXT NOT NULL,
    config_json     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    UNIQUE (owner_id, marketplace_id)
);
CREATE INDEX IF NOT EXISTS idx_marketplaces_owner ON marketplaces (owner_id);

-- Agent session-default mounts (merged when creating sessions if caller omits them).
ALTER TABLE agents ADD COLUMN IF NOT EXISTS default_environment_id TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS default_vault_ids_json TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS default_memory_store_ids_json TEXT;

-- Last accepted physical Managed AgentTask scope for monotonic runtime status
-- projection. This lives with the product Session because the runtime Store may
-- use a different PostgreSQL database or the in-memory development driver.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_agent_task_id TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_attempt_id TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_dispatch_generation BIGINT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS runtime_turn_id TEXT;
`

type productMigration struct {
	version string
	sql     string
}

// New schema changes append a migration; published SQL must not be edited.
var productMigrations = []productMigration{
	{"0001_product_resources", migrationSQL + channelWorkMigrationSQL + oauthMigrationSQL + workspacePublicationMigration},
	{"0002_product_resource_columns", migrationAlterSQL},
	{"0003_product_accounts", accountManagementSQL},
	{"0004_product_policy_changes", policychanges.SchemaSQL + policyChangeTriggers},
	{"0005_environment_execution_modes", `
      UPDATE environments SET type='local' WHERE type='self_hosted';
      UPDATE environments SET type='cloud' WHERE type='sandbox';
      UPDATE environments SET config_json=(COALESCE(NULLIF(config_json,'null')::jsonb,'{}'::jsonb) || jsonb_build_object('type',type))::text;
    `},
	{"0006_remove_managed_runtime_markers", `
  ALTER TABLE sessions DROP COLUMN IF EXISTS runtime_agent_task_id;
  ALTER TABLE sessions DROP COLUMN IF EXISTS runtime_attempt_id;
  ALTER TABLE sessions DROP COLUMN IF EXISTS runtime_dispatch_generation;
  ALTER TABLE sessions DROP COLUMN IF EXISTS runtime_turn_id;
 `},
	{"0007_integrated_scheduler", schedulerSchemaSQL},
}

func migrationChecksum(migration productMigration) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(migration.sql)))
}

// MigrateSchema runs without starting the product API, creating a workspace,
// loading credentials, or bootstrapping any users.
func MigrateSchema(ctx context.Context, dsn string) error {
	db, err := openDB(ctx, dsn)
	if err != nil {
		return err
	}
	defer db.Close()
	return migrate(ctx, db)
}

func migrate(ctx context.Context, db *DB) error {
	conn, err := db.Pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	const lock int64 = 0x41534350000001
	for {
		var acquired bool
		if err = conn.QueryRow(ctx, "SELECT pg_try_advisory_lock($1)", lock).Scan(&acquired); err != nil {
			return err
		}
		if acquired {
			break
		}
		timer := time.NewTimer(50 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
	defer func() { _, _ = conn.Exec(context.Background(), "SELECT pg_advisory_unlock($1)", lock) }()
	if _, err = conn.Exec(ctx, `CREATE SCHEMA IF NOT EXISTS cp;
		CREATE TABLE IF NOT EXISTS cp.product_schema_migrations (
		version TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return fmt.Errorf("product migration metadata: %w", err)
	}
	rows, err := conn.Query(ctx, "SELECT version,checksum FROM cp.product_schema_migrations ORDER BY version LIMIT 1025")
	if err != nil {
		return err
	}
	applied := map[string]string{}
	for rows.Next() {
		var version, checksum string
		if err = rows.Scan(&version, &checksum); err != nil {
			rows.Close()
			return err
		}
		applied[version] = checksum
	}
	rows.Close()
	if err = rows.Err(); err != nil {
		return err
	}
	if err = validateProductVersions(applied, false); err != nil {
		return err
	}
	for _, migration := range productMigrations {
		if _, exists := applied[migration.version]; exists {
			continue
		}
		tx, err := conn.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err = tx.Exec(ctx, migration.sql); err == nil {
			_, err = tx.Exec(ctx, "INSERT INTO cp.product_schema_migrations(version,checksum) VALUES($1,$2)", migration.version, migrationChecksum(migration))
		}
		if err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("product migrate %s: %w", migration.version, err)
		}
		if err = tx.Commit(ctx); err != nil {
			return err
		}
	}
	log.Printf("cp schema migration complete")
	return nil
}

func validateSchema(ctx context.Context, db *DB) error {
	rows, err := db.Pool.Query(ctx, "SELECT version,checksum FROM cp.product_schema_migrations ORDER BY version LIMIT 1025")
	if err != nil {
		return fmt.Errorf("product schema is not initialized; run the migration job: %w", err)
	}
	defer rows.Close()
	applied := map[string]string{}
	for rows.Next() {
		var version, checksum string
		if err = rows.Scan(&version, &checksum); err != nil {
			return err
		}
		applied[version] = checksum
	}
	if err = rows.Err(); err != nil {
		return err
	}
	return validateProductVersions(applied, true)
}

func validateProductVersions(applied map[string]string, complete bool) error {
	expected := make(map[string]string, len(productMigrations))
	for _, migration := range productMigrations {
		expected[migration.version] = migrationChecksum(migration)
		if complete && applied[migration.version] == "" {
			return fmt.Errorf("product schema migration is missing: %s; run the migration job", migration.version)
		}
	}
	for version, checksum := range applied {
		if expected[version] == "" {
			return fmt.Errorf("product schema version is unsupported: %s", version)
		}
		if expected[version] != checksum {
			return fmt.Errorf("product schema checksum differs: %s", version)
		}
	}
	return nil
}
