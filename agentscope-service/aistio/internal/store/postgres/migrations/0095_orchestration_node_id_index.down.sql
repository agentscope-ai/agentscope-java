-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_node_id_idx;
