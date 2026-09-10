-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY idx_agent_task_inputs_unique ON agent_task_inputs (task_id, comment_id, comment_version);
