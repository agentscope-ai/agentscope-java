-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS orchestration_event_dedupe_idx;
