-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_node_run_idx;
