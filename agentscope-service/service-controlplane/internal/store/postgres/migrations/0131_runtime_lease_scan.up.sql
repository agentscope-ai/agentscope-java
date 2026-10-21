CREATE INDEX agent_tasks_runtime_queue_cursor ON agent_tasks(id) WHERE status='queued';
CREATE INDEX execution_attempts_runtime_state_cursor ON execution_attempts(state,id)
 WHERE state IN ('assigned','preparing','running','waiting','cancel_requested');
