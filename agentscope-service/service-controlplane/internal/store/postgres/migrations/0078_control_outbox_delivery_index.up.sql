-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_control_outbox_delivery ON control_outbox (available_at, created_at) WHERE delivered_at IS NULL AND dead_lettered_at IS NULL;
