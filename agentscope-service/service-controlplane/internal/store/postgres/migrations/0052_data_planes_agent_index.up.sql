-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_data_planes_agent ON data_planes (agent_name, namespace);
