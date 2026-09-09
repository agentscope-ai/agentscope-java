-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_agent_instances_identity;
