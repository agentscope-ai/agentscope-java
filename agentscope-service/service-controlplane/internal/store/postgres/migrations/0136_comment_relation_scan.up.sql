-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_comment_mentions_scan ON comment_mentions(comment_id,created_at,id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_comment_routes_scan ON comment_routes(comment_id,created_at,id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_task_inputs_comment_version ON agent_task_inputs(comment_id,comment_version,task_id);
