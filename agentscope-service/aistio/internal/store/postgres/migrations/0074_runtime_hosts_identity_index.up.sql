-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_runtime_hosts_identity ON runtime_hosts (tenant, namespace, host_key);
