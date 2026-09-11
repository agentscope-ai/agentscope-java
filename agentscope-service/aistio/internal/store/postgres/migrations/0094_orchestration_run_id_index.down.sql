-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_run_id_idx;
