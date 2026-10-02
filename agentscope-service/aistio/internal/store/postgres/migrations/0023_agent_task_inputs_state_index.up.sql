-- +migrate NoTransaction
CREATE INDEX CONCURRENTLY idx_agent_task_inputs_state ON agent_task_inputs (task_id, state, sequence);
