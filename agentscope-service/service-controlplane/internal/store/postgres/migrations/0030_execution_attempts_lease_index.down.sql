-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_execution_attempts_lease;
