-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_comments_thread_time ON comments (issue_id, thread_root_id, created_at, id);
