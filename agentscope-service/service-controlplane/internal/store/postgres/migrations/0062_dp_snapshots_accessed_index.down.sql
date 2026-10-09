-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_dp_snapshots_accessed;
