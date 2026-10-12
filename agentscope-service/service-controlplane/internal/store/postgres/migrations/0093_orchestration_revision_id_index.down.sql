-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_revision_id_idx;
