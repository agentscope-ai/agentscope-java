-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_sessions_identity ON sessions (tenant, agent_id, session_id) WHERE agent_id IS NOT NULL;
CREATE UNIQUE INDEX CONCURRENTLY idx_sessions_legacy_identity ON sessions (tenant, agent_name, namespace, session_id) WHERE agent_id IS NULL;
