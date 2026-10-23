-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_edge_id_idx;
