-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_execution_attempts_agent_task ON execution_attempts (agent_task_id, attempt);
