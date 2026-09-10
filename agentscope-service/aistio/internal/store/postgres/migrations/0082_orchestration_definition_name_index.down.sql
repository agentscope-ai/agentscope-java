-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_definition_name_idx;
