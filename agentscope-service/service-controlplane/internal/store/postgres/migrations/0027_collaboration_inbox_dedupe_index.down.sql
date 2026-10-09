-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_inbox_items_dedupe;
