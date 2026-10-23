-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_transcript_index_updated;
