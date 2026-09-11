-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_agent_metrics_time;
