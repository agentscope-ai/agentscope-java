-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_definition_id_idx;
