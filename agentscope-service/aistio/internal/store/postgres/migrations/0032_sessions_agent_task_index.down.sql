-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_sessions_agent_task;
