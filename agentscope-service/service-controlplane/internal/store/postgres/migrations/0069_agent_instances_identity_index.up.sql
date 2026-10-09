-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_agent_instances_identity ON agent_instances (agent_id, binding_id, instance_key);
