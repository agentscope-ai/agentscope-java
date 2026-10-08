-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_inbox_items_recipient ON inbox_items (tenant, namespace, recipient_ref, archived, created_at DESC);
