-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_run_scope_idx;
