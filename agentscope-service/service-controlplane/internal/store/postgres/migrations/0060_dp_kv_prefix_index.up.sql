-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_dp_kv_prefix ON dp_kv (tenant, ns_path text_pattern_ops, item_key);
