-- +migrate NoTransaction
DROP INDEX CONCURRENTLY IF EXISTS idx_agent_task_inputs_state;
