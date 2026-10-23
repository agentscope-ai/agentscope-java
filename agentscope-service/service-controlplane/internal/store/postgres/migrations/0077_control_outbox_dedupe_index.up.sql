-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_control_outbox_dedupe ON control_outbox (tenant, dedupe_key) WHERE dedupe_key IS NOT NULL;
