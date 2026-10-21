-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_task_inputs_sequence ON agent_task_inputs(task_id,sequence,id);
