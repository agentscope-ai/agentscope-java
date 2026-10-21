CREATE INDEX IF NOT EXISTS orchestration_nodes_active_cancel_cursor
 ON orchestration_run_nodes(run_id,id)
 WHERE state NOT IN ('succeeded','failed','cancelled','skipped');

CREATE INDEX IF NOT EXISTS agent_tasks_active_node_cancel
 ON agent_tasks(run_node_id,priority DESC,created_at,id)
 WHERE status NOT IN ('completed','failed','cancelled');

CREATE INDEX IF NOT EXISTS orchestration_runs_active_parent_cancel
 ON orchestration_runs(parent_node_id,created_at,id)
 WHERE state NOT IN ('succeeded','partial_succeeded','failed','cancelled');
