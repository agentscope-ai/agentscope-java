-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_comment_mentions_scan;
DROP INDEX CONCURRENTLY IF EXISTS idx_comment_routes_scan;
DROP INDEX CONCURRENTLY IF EXISTS idx_agent_task_inputs_comment_version;
