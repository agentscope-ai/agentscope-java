-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS agent_runtime_policy_id_idx;
