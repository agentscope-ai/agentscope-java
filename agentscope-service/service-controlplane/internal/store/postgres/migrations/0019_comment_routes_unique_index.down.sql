-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_comment_routes_unique;
