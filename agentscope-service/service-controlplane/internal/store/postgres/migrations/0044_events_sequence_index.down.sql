-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_events_sequence;
