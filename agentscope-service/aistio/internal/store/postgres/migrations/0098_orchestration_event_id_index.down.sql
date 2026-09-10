-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_event_id_idx;
