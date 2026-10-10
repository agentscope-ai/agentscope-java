-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_transcript_index_updated ON session_transcript_index (updated_at DESC);
