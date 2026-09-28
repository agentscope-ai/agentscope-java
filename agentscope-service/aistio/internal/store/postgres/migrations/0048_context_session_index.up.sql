-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_ctx_session_time ON context_snapshots (session_fk, captured_at DESC);
