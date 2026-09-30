-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_agent_tasks_pending_unique;
