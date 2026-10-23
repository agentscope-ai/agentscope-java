-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_snapshots_session_time ON session_snapshots (session_fk, captured_at DESC);
