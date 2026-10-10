-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_comments_issue_time ON comments (issue_id, created_at, id);
