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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package product

const oauthMigrationSQL = `
CREATE TABLE IF NOT EXISTS mcp_oauth_connections (
    connection_id TEXT PRIMARY KEY,
    vault_id TEXT NOT NULL REFERENCES vaults(vault_id) ON DELETE CASCADE,
    owner_id TEXT NOT NULL,
    server_name TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    settings_ciphertext BYTEA NOT NULL,
    credential_id TEXT,
    generation BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(vault_id, endpoint)
);
CREATE TABLE IF NOT EXISTS mcp_oauth_flows (
    flow_id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL REFERENCES mcp_oauth_connections(connection_id) ON DELETE CASCADE,
    owner_id TEXT NOT NULL,
    initiator_id TEXT NOT NULL,
    generation BIGINT NOT NULL,
    state_hash TEXT NOT NULL UNIQUE,
    browser_hash TEXT NOT NULL,
    request_ciphertext BYTEA,
    token_ciphertext BYTEA,
    status TEXT NOT NULL,
    error_code TEXT NOT NULL DEFAULT '',
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS mcp_oauth_flows_expiry ON mcp_oauth_flows(expires_at);
ALTER TABLE mcp_oauth_connections ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT '';
ALTER TABLE mcp_oauth_connections ADD COLUMN IF NOT EXISTS account_json TEXT NOT NULL DEFAULT '{}';
CREATE TABLE IF NOT EXISTS oauth_provider_apps (
    provider TEXT PRIMARY KEY,
    settings_ciphertext BYTEA NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
`
