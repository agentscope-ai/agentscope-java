-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_inbox_items_dedupe ON inbox_items (tenant, dedupe_key) WHERE dedupe_key IS NOT NULL;
