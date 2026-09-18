-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_sessions_identity;
DROP INDEX CONCURRENTLY IF EXISTS idx_sessions_legacy_identity;
