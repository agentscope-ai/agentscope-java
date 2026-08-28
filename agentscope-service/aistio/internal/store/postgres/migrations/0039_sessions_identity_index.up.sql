-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_sessions_identity ON sessions (tenant, agent_name, namespace, session_id);
