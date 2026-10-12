-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_runtime_profiles_name ON runtime_profiles (tenant, namespace, name);
