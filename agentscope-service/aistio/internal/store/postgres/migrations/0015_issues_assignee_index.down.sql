-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_issues_assignee;
