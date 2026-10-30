-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS execution_attempt_run_idx;
