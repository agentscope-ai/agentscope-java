-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_runtime_pools_name ON runtime_pools (tenant, namespace, name);
