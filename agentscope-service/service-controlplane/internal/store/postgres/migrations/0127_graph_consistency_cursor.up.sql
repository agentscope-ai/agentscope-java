CREATE INDEX IF NOT EXISTS orchestration_nodes_run_cursor
 ON orchestration_run_nodes(run_id,id);
CREATE INDEX IF NOT EXISTS orchestration_edges_run_order
 ON orchestration_run_edges(run_id,ordinal,id);
CREATE INDEX IF NOT EXISTS agent_tasks_run_consistency_cursor
 ON agent_tasks(orchestration_run_id,id);
CREATE INDEX IF NOT EXISTS agent_tasks_node_consistency_cursor
 ON agent_tasks(run_node_id,id);
CREATE INDEX IF NOT EXISTS agent_tasks_issue_consistency_cursor
 ON agent_tasks(issue_id,id);
