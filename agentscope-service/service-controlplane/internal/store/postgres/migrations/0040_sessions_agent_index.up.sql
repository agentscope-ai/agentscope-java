-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_sessions_agent ON sessions (tenant, namespace, agent_id);
