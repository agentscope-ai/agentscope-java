-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_runtime_hosts_last_seen ON runtime_hosts (last_seen_at);
