-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_agent_instances_last_seen ON agent_instances (last_seen_at);
