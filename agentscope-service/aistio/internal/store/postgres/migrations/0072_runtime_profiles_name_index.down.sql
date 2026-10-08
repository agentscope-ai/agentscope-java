-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_runtime_profiles_name;
