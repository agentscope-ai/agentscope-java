-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_runtime_hosts_pool_state ON runtime_hosts (tenant, namespace, pool_name, state);
