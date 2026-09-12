-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_locks_expiry ON dp_locks (expires_at);
