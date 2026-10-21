CREATE INDEX IF NOT EXISTS orchestration_runs_root_keyset
 ON orchestration_runs(root_issue_id,created_at,id);

CREATE INDEX IF NOT EXISTS orchestration_nodes_autonomous_local
 ON orchestration_run_nodes(run_id,id)
 WHERE state IN ('ready','running') OR (state='waiting' AND type NOT IN ('approval','signal','subrun'));
