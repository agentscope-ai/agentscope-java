-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_snapshots_accessed ON dp_snapshots (accessed_at);
