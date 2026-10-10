-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_run_idempotency_idx;
