-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_activity_log_issue ON activity_log (issue_id, created_at, id) WHERE issue_id IS NOT NULL;
