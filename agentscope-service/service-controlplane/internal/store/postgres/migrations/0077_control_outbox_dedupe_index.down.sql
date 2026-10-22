-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_control_outbox_dedupe;
