-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_runtime_hosts_last_seen;
