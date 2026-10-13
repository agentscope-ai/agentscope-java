-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_issue_subscribers_unique;
