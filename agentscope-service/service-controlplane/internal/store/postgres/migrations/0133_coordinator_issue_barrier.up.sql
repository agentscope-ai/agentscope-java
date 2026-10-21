-- Locate coordinator barriers for unassigned child Issue writes.
CREATE INDEX IF NOT EXISTS idx_run_nodes_team_issue_barrier ON orchestration_run_nodes(issue_id,run_id) WHERE type='team';
