-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_snapshots_session_time;
