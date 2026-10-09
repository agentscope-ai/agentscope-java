-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_data_planes_seen ON data_planes (last_seen_at DESC);
