-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_sessions_agent ON sessions (tenant, agent_name, namespace);
