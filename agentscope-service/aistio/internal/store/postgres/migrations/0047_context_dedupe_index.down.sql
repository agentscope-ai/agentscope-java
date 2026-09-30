-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_ctx_dedup;
