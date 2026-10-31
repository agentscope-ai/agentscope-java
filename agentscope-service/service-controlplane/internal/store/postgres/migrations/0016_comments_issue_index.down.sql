-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_comments_issue_time;
