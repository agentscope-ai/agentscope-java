-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_agent_instances_last_seen;
