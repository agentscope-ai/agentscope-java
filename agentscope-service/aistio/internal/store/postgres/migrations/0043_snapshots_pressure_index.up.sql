-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_snapshots_pressure ON session_snapshots (captured_at, context_pressure) WHERE context_pressure > 0.7;
