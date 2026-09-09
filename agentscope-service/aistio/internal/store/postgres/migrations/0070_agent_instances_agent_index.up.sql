-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_agent_instances_agent ON agent_instances (tenant, namespace, agent_id);
