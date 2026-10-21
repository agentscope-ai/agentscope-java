CREATE INDEX agent_task_inputs_retry_due ON agent_task_inputs(next_attempt_at,id)
 WHERE state='retrying';
